package io.continuum.admission;

import io.continuum.persistence.entity.AdmissionSettingEntity;
import io.continuum.persistence.repository.AdmissionSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides whether a request is allowed to reach a provider right now.
 *
 * <p>Without this, load is handled by the provider: a burst produces a wall of
 * 429s, every one of them retried, which produces a larger burst. The retry
 * storm is the failure, not the original load.
 *
 * <p>With it, Continuum holds concurrency at the limit it has inferred from
 * latency (see {@link ConcurrencyLimiter}), lets a request wait briefly for a
 * slot, and — when there is genuinely no room — refuses the least important
 * traffic first and says so immediately. <b>A fast, honest refusal is worth more
 * than a slow one</b>: the caller can retry later, degrade, or tell its user,
 * none of which it can do while blocked.
 *
 * <p>Limits are held per <i>tenant and provider</i>, not globally. With
 * bring-your-own-key each account has its own quota at the provider, so one
 * account's burst is not evidence about another's capacity. On a shared platform
 * key that assumption breaks and the limits are independently optimistic — noted
 * rather than hidden.
 */
@Service
public class AdmissionService {

    /** How long a waiting request will hold on for a slot before being refused. */
    private static final long DEFAULT_QUEUE_MS = 250;
    /** Rolling limit history kept per key, for the console's live trace. */
    private static final int HISTORY = 60;

    private final AdmissionSettingRepository repo;

    private final Map<String, ConcurrencyLimiter> limiters = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Counters> counters = new ConcurrentHashMap<>();
    private final Map<String, Deque<Integer>> history = new ConcurrentHashMap<>();

    public AdmissionService(AdmissionSettingRepository repo) {
        this.repo = repo;
    }

    /** Per-key tallies, for the console. */
    private static final class Counters {
        final AtomicLong admitted = new AtomicLong();
        final AtomicLong queued = new AtomicLong();
        final AtomicLong shed = new AtomicLong();
        final Map<String, AtomicLong> shedBy = new ConcurrentHashMap<>();
        final AtomicLong peakInFlight = new AtomicLong();
    }

    /** Raised when a request is deliberately refused. */
    public static class SheddedException extends RuntimeException {
        private final Criticality criticality;
        private final int limit;
        private final int inFlight;

        public SheddedException(String message, Criticality criticality, int limit, int inFlight) {
            super(message);
            this.criticality = criticality;
            this.limit = limit;
            this.inFlight = inFlight;
        }

        public Criticality criticality() {
            return criticality;
        }

        public int limit() {
            return limit;
        }

        public int inFlight() {
            return inFlight;
        }
    }

    /**
     * A held slot. Released exactly once, in a finally block, carrying the
     * latency so the limiter can learn from it.
     */
    public final class Slot implements AutoCloseable {
        private final String key;
        private final long startNanos = System.nanoTime();
        private final int inFlightAtStart;
        private boolean closed;

        private Slot(String key, int inFlightAtStart) {
            this.key = key;
            this.inFlightAtStart = inFlightAtStart;
        }

        /** The call succeeded; feed the latency into the limit estimate. */
        public void success() {
            if (closed) {
                return;
            }
            limiter(key).onSuccess((System.nanoTime() - startNanos) / 1_000_000.0, inFlightAtStart);
            close();
        }

        /** The provider refused or timed out; back off. */
        public void dropped() {
            if (closed) {
                return;
            }
            limiter(key).onDrop();
            close();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            counted(key).decrementAndGet();
            recordHistory(key);
        }
    }

    // --- admission -----------------------------------------------------------

    @Transactional(readOnly = true)
    public boolean enabled(String developerId) {
        return repo.findById(developerId).map(AdmissionSettingEntity::isEnabled).orElse(false);
    }

    /**
     * Acquires a slot, waiting briefly if the provider is busy.
     *
     * @throws SheddedException when there is no room for traffic of this importance
     */
    public Slot acquire(String developerId, String provider, Criticality criticality) {
        String key = key(developerId, provider);
        ConcurrencyLimiter l = limiter(key);
        AtomicInteger n = counted(key);
        Counters c = counters(key);

        long deadline = System.nanoTime() + DEFAULT_QUEUE_MS * 1_000_000L;
        boolean waited = false;

        while (true) {
            int limit = l.limit();
            int current = n.get();
            // The shedding point scales with importance, so background work is
            // refused while there is still room for an interactive request.
            double ceiling = limit * criticality.sheddingPoint();

            if (current < ceiling) {
                int taken = n.incrementAndGet();
                // Re-check: another thread may have taken the slot between the
                // read and the increment. Losing the race means giving it back.
                if (taken <= ceiling) {
                    c.admitted.incrementAndGet();
                    if (waited) {
                        c.queued.incrementAndGet();
                    }
                    c.peakInFlight.accumulateAndGet(taken, Math::max);
                    recordHistory(key);
                    return new Slot(key, taken - 1);
                }
                n.decrementAndGet();
            }

            if (System.nanoTime() >= deadline) {
                c.shed.incrementAndGet();
                c.shedBy.computeIfAbsent(criticality.name(), k -> new AtomicLong()).incrementAndGet();
                recordHistory(key);
                throw new SheddedException(
                        "The provider is at its inferred capacity (" + limit
                                + " concurrent) and this request is " + criticality.name().toLowerCase()
                                + " priority. Retry shortly.", criticality, limit, current);
            }

            waited = true;
            try {
                // Short sleep rather than a condition variable: the wait is
                // bounded at a quarter second, and a queue that needs precise
                // wake-ups is a queue that is already too long.
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SheddedException("Interrupted while waiting for capacity.",
                        criticality, l.limit(), n.get());
            }
        }
    }

    // --- state ---------------------------------------------------------------

    private static String key(String developerId, String provider) {
        return developerId + "|" + provider;
    }

    private ConcurrencyLimiter limiter(String key) {
        return limiters.computeIfAbsent(key, k -> new ConcurrencyLimiter());
    }

    private AtomicInteger counted(String key) {
        return inFlight.computeIfAbsent(key, k -> new AtomicInteger());
    }

    private Counters counters(String key) {
        return counters.computeIfAbsent(key, k -> new Counters());
    }

    private void recordHistory(String key) {
        Deque<Integer> d = history.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (d) {
            d.addLast(limiter(key).limit());
            while (d.size() > HISTORY) {
                d.removeFirst();
            }
        }
    }

    // --- console -------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> status(String developerId) {
        AdmissionSettingEntity s = repo.findById(developerId).orElseGet(
                () -> new AdmissionSettingEntity(developerId));

        List<Map<String, Object>> providers = new ArrayList<>();
        String prefix = developerId + "|";
        for (Map.Entry<String, ConcurrencyLimiter> e : limiters.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            String provider = e.getKey().substring(prefix.length());
            Counters c = counters(e.getKey());
            Map<String, Object> m = new LinkedHashMap<>(e.getValue().describe());
            m.put("provider", provider);
            m.put("inFlight", counted(e.getKey()).get());
            m.put("peakInFlight", c.peakInFlight.get());
            m.put("admitted", c.admitted.get());
            m.put("queued", c.queued.get());
            m.put("shed", c.shed.get());
            Map<String, Object> by = new LinkedHashMap<>();
            c.shedBy.forEach((k, v) -> by.put(k, v.get()));
            m.put("shedBy", by);
            Deque<Integer> d = history.get(e.getKey());
            synchronized (d == null ? new Object() : d) {
                m.put("limitHistory", d == null ? List.of() : new ArrayList<>(d));
            }
            providers.add(m);
        }
        providers.sort((a, b) -> String.valueOf(a.get("provider")).compareTo(String.valueOf(b.get("provider"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", s.isEnabled());
        out.put("queueMs", DEFAULT_QUEUE_MS);
        out.put("providers", providers);
        Map<String, Object> levels = new LinkedHashMap<>();
        for (Criticality c : Criticality.values()) {
            levels.put(c.name(), c.sheddingPoint());
        }
        out.put("sheddingPoints", levels);
        return out;
    }

    @Transactional
    public Map<String, Object> configure(String developerId, Boolean enabled) {
        AdmissionSettingEntity s = repo.findById(developerId)
                .orElseGet(() -> new AdmissionSettingEntity(developerId));
        if (enabled != null) {
            s.setEnabled(enabled);
        }
        repo.save(s);
        return status(developerId);
    }

    /** Clears learned limits and tallies for one tenant. */
    public void reset(String developerId) {
        String prefix = developerId + "|";
        limiters.keySet().removeIf(k -> k.startsWith(prefix));
        inFlight.keySet().removeIf(k -> k.startsWith(prefix));
        counters.keySet().removeIf(k -> k.startsWith(prefix));
        history.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
