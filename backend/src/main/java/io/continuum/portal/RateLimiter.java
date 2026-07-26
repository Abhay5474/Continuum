package io.continuum.portal;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A token bucket per caller.
 *
 * <p>Buckets refill continuously rather than resetting on a boundary, so a caller
 * cannot save up a whole window's allowance and spend it in one burst at the
 * turn of the minute.
 *
 * <p>State is in memory, which means the limit is per instance. That is a real
 * limitation behind a load balancer — three instances allow three times the
 * traffic — but it is honest and it removes the unbounded case, which is what
 * matters: before this, an attacker could send password guesses as fast as the
 * network allowed.
 */
@Component
public class RateLimiter {

    /** How long an idle bucket is kept before it can be swept. */
    private static final Duration IDLE_TTL = Duration.ofMinutes(30);
    private static final int SWEEP_EVERY = 512;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private int sinceSweep = 0;

    /**
     * Takes one token for {@code key}.
     *
     * @param capacity  burst size — the most that can be spent at once
     * @param perMinute sustained rate
     * @return the outcome, including how long to wait when refused
     */
    public Decision take(String key, int capacity, double perMinute) {
        maybeSweep();
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        return b.take(capacity, perMinute / 60.0);
    }

    /** Forgets a caller's bucket — used after a success that should not count against them. */
    public void reset(String key) {
        buckets.remove(key);
    }

    /** Cheap amortised cleanup; no scheduler needed for a map this small. */
    private void maybeSweep() {
        if (++sinceSweep < SWEEP_EVERY) {
            return;
        }
        sinceSweep = 0;
        Instant cutoff = Instant.now().minus(IDLE_TTL);
        buckets.entrySet().removeIf(e -> e.getValue().lastSeen().isBefore(cutoff));
    }

    /**
     * @param allowed          whether the caller may proceed
     * @param remaining        tokens left after this call
     * @param retryAfterSeconds how long until one token is available
     */
    public record Decision(boolean allowed, int remaining, long retryAfterSeconds) {
    }

    private static final class Bucket {
        private double tokens;
        private Instant updated = Instant.now();

        Bucket(int capacity) {
            this.tokens = capacity;
        }

        synchronized Instant lastSeen() {
            return updated;
        }

        synchronized Decision take(int capacity, double perSecond) {
            Instant now = Instant.now();
            double elapsed = Duration.between(updated, now).toMillis() / 1000.0;
            updated = now;
            tokens = Math.min(capacity, tokens + elapsed * perSecond);

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return new Decision(true, (int) tokens, 0);
            }
            long wait = (long) Math.ceil((1.0 - tokens) / Math.max(perSecond, 1e-9));
            return new Decision(false, 0, Math.max(1, wait));
        }
    }
}
