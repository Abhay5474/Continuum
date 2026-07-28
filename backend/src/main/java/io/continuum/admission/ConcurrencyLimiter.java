package io.continuum.admission;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Infers how much concurrency a provider will actually take, from latency.
 *
 * <p>The usual way to bound calls to a provider is a number somebody typed. That
 * number is wrong on the first day and wronger every day after: providers change
 * their limits, other traffic shares the same key, and a quota that was generous
 * at 3am is not generous at peak.
 *
 * <p>So the limit is measured instead of configured. This is <b>TCP Vegas</b>
 * (Brakmo &amp; Peterson, 1995) applied to RPC, the way Netflix's adaptive
 * concurrency limits do it: congestion shows up as latency rising above the
 * best latency ever seen, <em>before</em> anything is dropped. Waiting for
 * errors — 429s, timeouts — means only reacting after the damage.
 *
 * <p>The core is one ratio:
 *
 * <pre>
 *   gradient  = minRtt / currentRtt        (1.0 when uncongested, → 0 when queueing)
 *   headroom  = sqrt(limit)                (Little's Law: queue grows with √capacity)
 *   newLimit  = limit × gradient + headroom
 * </pre>
 *
 * <p>Three details matter more than the formula.
 *
 * <p><b>It only grows when it is being used.</b> A limit raised while eight
 * requests use a limit of eighty is not evidence of capacity, it is evidence of
 * an idle system. Without this guard the limit ratchets upward during quiet
 * periods and the first burst discovers it was fiction.
 *
 * <p><b>Down fast, up slow.</b> The gradient is clamped so no single sample can
 * halve the limit, but a drop — a 429, a timeout — cuts it multiplicatively at
 * once. Overshooting capacity costs a retry storm; undershooting costs a little
 * throughput.
 *
 * <p><b>The baseline decays.</b> {@code minRtt} is the best latency seen, but
 * held forever it becomes unreachable: one lucky fast response early on makes
 * every later response look congested. It is nudged upward slowly when nothing
 * beats it, so the baseline tracks reality instead of a memory.
 */
public final class ConcurrencyLimiter {

    /** Where a fresh limiter starts. Low enough to be safe, high enough to probe. */
    public static final double INITIAL = 8;
    public static final double MIN = 1;
    public static final double MAX = 200;

    /** A single sample may not cut the limit by more than half. */
    private static final double MIN_GRADIENT = 0.5;
    /** Multiplicative decrease on an actual drop. */
    private static final double BACKOFF = 0.8;
    /** Grow only when at least this fraction of the limit is in use. */
    private static final double UTILISATION_TO_GROW = 0.5;
    /** How fast the latency baseline is allowed to drift upward. */
    private static final double BASELINE_DECAY = 1.0005;

    /**
     * Below this, a latency difference is not a congestion signal.
     *
     * <p>Found by driving 120 concurrent requests at a provider that answers in
     * 3ms: queueing added another 3ms, the gradient read 0.48, and the limit
     * collapsed to 4 on a provider that was not remotely congested. At those
     * timescales the difference is thread scheduling and garbage collection, not
     * a queue.
     *
     * <p>Real hosted models sit between 200ms and several seconds, where a few
     * milliseconds of jitter is invisible — but a local model, a cached
     * endpoint or a stub is fast enough to make the ratio meaningless. Both
     * sides of the ratio are floored, so a fast provider yields a gradient of
     * 1.0 and the limit is left alone rather than being throttled by noise.
     */
    private static final double NOISE_FLOOR_MS = 20;

    private double limit = INITIAL;
    private double minRttMs = Double.NaN;
    private double lastRttMs = Double.NaN;
    private long samples;
    private long drops;

    /** The limit right now, as a whole number of permits. */
    public int limit() {
        return (int) Math.max(MIN, Math.round(limit));
    }

    public double rawLimit() {
        return limit;
    }

    public double minRttMs() {
        return Double.isNaN(minRttMs) ? 0 : minRttMs;
    }

    public double lastRttMs() {
        return Double.isNaN(lastRttMs) ? 0 : lastRttMs;
    }

    public long samples() {
        return samples;
    }

    public long drops() {
        return drops;
    }

    /**
     * A call came back.
     *
     * @param rttMs    how long it took
     * @param inFlight how many were running when it started
     */
    public synchronized void onSuccess(double rttMs, int inFlight) {
        if (rttMs <= 0) {
            return;
        }
        samples++;
        lastRttMs = rttMs;

        if (Double.isNaN(minRttMs) || rttMs < minRttMs) {
            minRttMs = rttMs;
        } else {
            // Nothing beat the baseline, so let it drift up a hair. Held
            // forever, one lucky early sample makes everything after it look
            // congested and the limit never recovers.
            minRttMs = Math.min(minRttMs * BASELINE_DECAY, rttMs);
        }

        double gradient = Math.max(MIN_GRADIENT, Math.min(1.0, gradient(minRttMs, rttMs)));
        double headroom = Math.sqrt(Math.max(1, limit));
        double candidate = limit * gradient + headroom;

        if (candidate > limit && inFlight < limit * UTILISATION_TO_GROW) {
            // Idle. Raising the limit here would be inventing capacity nobody
            // has demonstrated, and the first real burst would find it out.
            return;
        }
        limit = clamp(candidate);
    }

    /**
     * A call was rejected or timed out.
     *
     * <p>Multiplicative decrease, immediately. By the time a provider is
     * answering 429 the gradient signal has already been ignored for a while.
     */
    public synchronized void onDrop() {
        drops++;
        limit = clamp(limit * BACKOFF);
    }

    /**
     * The congestion signal, with both sides floored so sub-millisecond
     * differences on a fast provider cannot masquerade as a queue.
     */
    static double gradient(double minRtt, double rtt) {
        return Math.max(minRtt, NOISE_FLOOR_MS) / Math.max(rtt, NOISE_FLOOR_MS);
    }

    private static double clamp(double v) {
        return Math.max(MIN, Math.min(MAX, v));
    }

    public synchronized Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("limit", limit());
        m.put("rawLimit", Math.round(limit * 100) / 100.0);
        m.put("minRttMs", Math.round(minRttMs()));
        m.put("lastRttMs", Math.round(lastRttMs()));
        m.put("gradient", Double.isNaN(minRttMs) || Double.isNaN(lastRttMs) || lastRttMs <= 0
                ? 1.0 : Math.round(Math.min(1, gradient(minRttMs, lastRttMs)) * 100) / 100.0);
        m.put("samples", samples);
        m.put("drops", drops);
        return m;
    }
}
