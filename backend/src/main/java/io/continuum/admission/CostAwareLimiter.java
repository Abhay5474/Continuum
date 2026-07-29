package io.continuum.admission;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting by what a request actually consumes, not by how many there are.
 *
 * <p>Counting requests treats a fifty-step agent conversation carrying twenty
 * thousand tokens of context the same as {@code "hello"}. They are not the same:
 * one of them is three orders of magnitude more expensive to serve, and under a
 * request-count limit the caller sending it is being subsidised by everyone
 * sending small ones.
 *
 * <p>Weighted fair queuing (Demers, Keshav &amp; Shenker, SIGCOMM 1989) solved
 * this for networks by charging per <em>bit</em> rather than per packet. Tokens
 * are the bits here and the generalisation is direct.
 *
 * <p><b>Two resources, and the tighter one binds.</b> Requests and tokens are
 * limited separately, and a caller is admitted only when both have room. This is
 * the two-resource case of Dominant Resource Fairness (Ghodsi et al., NSDI
 * 2011): whichever resource a caller consumes most of, relative to their
 * allowance, is the one that limits them. Someone making many tiny calls is
 * bounded by the request limit; someone making one enormous call is bounded by
 * the token limit; neither can starve the other by choosing a shape.
 *
 * <p><b>Reserve, then settle.</b> A request's real token cost is not known until
 * the response comes back, so admission reserves an estimate and the true figure
 * is settled afterwards. Without settlement the limiter would drift from
 * reality in whichever direction the estimate was biased — and an
 * <em>under</em>-estimate that is never corrected is a hole in the limit.
 *
 * <p><b>An unsettled reservation is a leak.</b> A reservation whose request died
 * before settling would hold capacity nobody is using, forever. Every
 * reservation is therefore returned in a finally block, and one that is never
 * settled expires on its own after {@link #RESERVATION_TTL}.
 */
public final class CostAwareLimiter {

    /** How long an unsettled reservation is held before it is assumed abandoned. */
    public static final Duration RESERVATION_TTL = Duration.ofMinutes(5);
    /** How long an idle caller's buckets are kept. */
    private static final Duration IDLE_TTL = Duration.ofMinutes(30);

    /**
     * Assumed completion length when the caller did not cap it.
     *
     * <p>Deliberately generous. Under-reserving lets a caller through and then
     * discovers the cost afterwards, which is the failure this class exists to
     * prevent; over-reserving only makes them wait slightly longer, and
     * settlement gives the excess straight back.
     */
    public static final int ASSUMED_COMPLETION_TOKENS = 800;

    private final Map<String, Caller> callers = new ConcurrentHashMap<>();

    /** Which resource refused, so the caller is told something actionable. */
    public enum Resource { REQUESTS, TOKENS }

    /**
     * @param allowed           whether the caller may proceed
     * @param boundBy           the resource that is closest to its limit
     * @param requestShare      fraction of the request allowance in use, 0–1+
     * @param tokenShare        fraction of the token allowance in use, 0–1+
     * @param reservedTokens    what was set aside for this request
     * @param retryAfterSeconds how long until there is room
     */
    public record Decision(boolean allowed, Resource boundBy, double requestShare,
                           double tokenShare, int reservedTokens, long retryAfterSeconds,
                           String reason) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("allowed", allowed);
            m.put("boundBy", boundBy.name());
            m.put("requestShare", Math.round(requestShare * 1000) / 1000.0);
            m.put("tokenShare", Math.round(tokenShare * 1000) / 1000.0);
            m.put("reservedTokens", reservedTokens);
            m.put("retryAfterSeconds", retryAfterSeconds);
            m.put("reason", reason);
            return m;
        }
    }

    /**
     * The decision, and the reservation it created.
     *
     * <p>They travel together so a caller cannot hold a reservation without
     * knowing it, or be admitted without the means to settle. {@code ticket} is
     * null exactly when {@code decision.allowed()} is false.
     */
    public record Outcome(Decision decision, Ticket ticket) {

        static Outcome refused(Decision d) {
            return new Outcome(d, null);
        }

        public boolean allowed() {
            return decision.allowed();
        }
    }

    /**
     * What a caller has consumed. Two continuously-refilling buckets, so nobody
     * can save up a minute's allowance and spend it in one burst.
     */
    private static final class Caller {
        double requestTokens;
        double costTokens;
        Instant updated = Instant.now();
        // Live reservations, so an abandoned one can be reclaimed.
        final Map<Long, Reservation> outstanding = new ConcurrentHashMap<>();
        long admitted;
        long refused;
        long refusedByTokens;
        long peakTokenShare;
        long totalTokensCharged;

        Caller(int requestCapacity, int tokenCapacity) {
            this.requestTokens = requestCapacity;
            this.costTokens = tokenCapacity;
        }

        synchronized void refill(int requestCapacity, int tokenCapacity,
                                 double requestsPerSec, double tokensPerSec) {
            Instant now = Instant.now();
            double elapsed = Duration.between(updated, now).toMillis() / 1000.0;
            updated = now;
            requestTokens = Math.min(requestCapacity, requestTokens + elapsed * requestsPerSec);
            costTokens = Math.min(tokenCapacity, costTokens + elapsed * tokensPerSec);
            expireStale(now);
        }

        /** Reclaims capacity held by requests that never came back. */
        private void expireStale(Instant now) {
            Instant cutoff = now.minus(RESERVATION_TTL);
            outstanding.values().removeIf(r -> {
                if (r.at.isBefore(cutoff)) {
                    costTokens += r.tokens;
                    return true;
                }
                return false;
            });
        }
    }

    private record Reservation(int tokens, Instant at) {
    }

    private static long nextId = 0;

    private static synchronized long newId() {
        return ++nextId;
    }

    /**
     * A held reservation. {@link AutoCloseable} because a reservation that is
     * never returned holds capacity nobody is using.
     */
    public final class Ticket implements AutoCloseable {
        private final String key;
        private final long id;
        private final int reserved;
        private boolean settled;

        private Ticket(String key, long id, int reserved) {
            this.key = key;
            this.id = id;
            this.reserved = reserved;
        }

        public int reservedTokens() {
            return reserved;
        }

        /**
         * Records what the request really cost and returns the difference.
         *
         * @param actualTokens prompt plus completion tokens actually used
         */
        public void settle(int actualTokens) {
            if (settled) {
                return;
            }
            settled = true;
            Caller c = callers.get(key);
            if (c == null) {
                return;
            }
            synchronized (c) {
                c.outstanding.remove(id);
                // Give back what was over-reserved; take the rest if it cost more.
                c.costTokens += reserved - Math.max(0, actualTokens);
                c.totalTokensCharged += Math.max(0, actualTokens);
            }
        }

        /** The call never happened; return the whole reservation. */
        @Override
        public void close() {
            if (settled) {
                return;
            }
            settled = true;
            Caller c = callers.get(key);
            if (c == null) {
                return;
            }
            synchronized (c) {
                if (c.outstanding.remove(id) != null) {
                    c.costTokens += reserved;
                }
            }
        }
    }

    /**
     * Reserves capacity for a request.
     *
     * @param promptTokens   size of the prompt being sent
     * @param maxTokens      the caller's completion cap, or null
     * @param requestsPerMin sustained request allowance
     * @param tokensPerMin   sustained token allowance
     */
    public Outcome reserve(String key, int promptTokens, Integer maxTokens,
                           int requestsPerMin, int tokensPerMin) {
        int requestCapacity = Math.max(1, requestsPerMin);
        int tokenCapacity = Math.max(1, tokensPerMin);
        Caller c = callers.computeIfAbsent(key, k -> new Caller(requestCapacity, tokenCapacity));

        int estimate = Math.max(1, promptTokens)
                + (maxTokens != null && maxTokens > 0 ? maxTokens : ASSUMED_COMPLETION_TOKENS);

        synchronized (c) {
            c.refill(requestCapacity, tokenCapacity,
                    requestCapacity / 60.0, tokenCapacity / 60.0);

            double requestShare = 1 - (c.requestTokens / requestCapacity);
            double tokenShare = 1 - (c.costTokens / tokenCapacity);
            // The dominant resource: whichever this caller is nearest to
            // exhausting, which is the one that decides their fate.
            Resource dominant = tokenShare >= requestShare ? Resource.TOKENS : Resource.REQUESTS;
            c.peakTokenShare = Math.max(c.peakTokenShare, Math.round(tokenShare * 100));

            if (c.requestTokens < 1.0) {
                c.refused++;
                long wait = (long) Math.ceil((1.0 - c.requestTokens) / (requestCapacity / 60.0));
                return Outcome.refused(new Decision(false, Resource.REQUESTS, requestShare,
                        tokenShare, 0, Math.max(1, wait),
                        "at the request limit of " + requestsPerMin + " per minute"));
            }
            if (c.costTokens < estimate) {
                c.refused++;
                c.refusedByTokens++;
                long wait = (long) Math.ceil((estimate - c.costTokens) / (tokenCapacity / 60.0));
                return Outcome.refused(new Decision(false, Resource.TOKENS, requestShare,
                        tokenShare, 0, Math.max(1, wait),
                        "this request needs about " + estimate + " tokens and the token allowance "
                                + "of " + tokensPerMin + " per minute is spent — a large request "
                                + "costs more of it than a small one, which is the point"));
            }

            c.requestTokens -= 1.0;
            c.costTokens -= estimate;
            c.admitted++;
            long id = newId();
            c.outstanding.put(id, new Reservation(estimate, Instant.now()));
            return new Outcome(
                    new Decision(true, dominant, requestShare, tokenShare, estimate, 0,
                            "reserved " + estimate + " tokens; bounded by "
                                    + dominant.name().toLowerCase(java.util.Locale.ROOT)),
                    new Ticket(key, id, estimate));
        }
    }

    /** Drops idle callers. */
    public void sweep() {
        Instant cutoff = Instant.now().minus(IDLE_TTL);
        callers.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().outstanding.isEmpty() && e.getValue().updated.isBefore(cutoff);
            }
        });
    }

    /** What each caller has consumed, for the console. */
    public List<Map<String, Object>> describe(String prefix, int requestsPerMin, int tokensPerMin) {
        List<Map<String, Object>> out = new ArrayList<>();
        callers.forEach((key, c) -> {
            if (prefix != null && !key.startsWith(prefix)) {
                return;
            }
            synchronized (c) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("caller", key);
                m.put("admitted", c.admitted);
                m.put("refused", c.refused);
                m.put("refusedByTokens", c.refusedByTokens);
                m.put("tokensCharged", c.totalTokensCharged);
                m.put("outstanding", c.outstanding.size());
                m.put("requestShare",
                        Math.round((1 - c.requestTokens / Math.max(1, requestsPerMin)) * 1000) / 1000.0);
                m.put("tokenShare",
                        Math.round((1 - c.costTokens / Math.max(1, tokensPerMin)) * 1000) / 1000.0);
                m.put("peakTokenSharePct", c.peakTokenShare);
                out.add(m);
            }
        });
        return out;
    }

    public void reset(String prefix) {
        callers.keySet().removeIf(k -> prefix == null || k.startsWith(prefix));
    }
}
