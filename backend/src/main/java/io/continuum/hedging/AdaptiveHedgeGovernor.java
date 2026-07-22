package io.continuum.hedging;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptive hedge governor: maintains a rolling window of recent request
 * latencies to compute the live p95 hedge trigger, and a rolling window of
 * recent hedge decisions to enforce the rate cap.
 *
 * Both windows are fixed-size ring buffers, so memory is bounded and old
 * samples age out — the estimate tracks non-stationary latency (a provider
 * getting slower shifts the p95 up automatically). Thread-safe for the
 * concurrent hedge workers.
 */
public class AdaptiveHedgeGovernor implements HedgeGovernor {

    private static final int LATENCY_WINDOW = 512;
    private static final int RATE_WINDOW = 256;
    private static final int WARMUP = 20; // fall back to the fixed threshold until enough data

    private final long[] latencies = new long[LATENCY_WINDOW];
    private final boolean[] hedgedFlags = new boolean[RATE_WINDOW];
    private final AtomicLong latencyCount = new AtomicLong();
    private final AtomicLong rateCount = new AtomicLong();

    // Cumulative counters for the profiler (before/after metrics).
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong totalHedged = new AtomicLong();

    @Override
    public long effectiveThresholdMs(HedgingPolicy policy) {
        if (!policy.adaptive()) {
            return policy.thresholdMs();
        }
        long seen = latencyCount.get();
        if (seen < WARMUP) {
            return policy.thresholdMs(); // not enough data yet — use the configured default
        }
        long p95 = percentile(0.95);
        return Math.max(policy.minThresholdMs(), p95);
    }

    @Override
    public boolean allowHedge(HedgingPolicy policy) {
        if (policy.hedgeRateCap() >= 1.0) {
            return true; // no cap configured
        }
        long seen = rateCount.get();
        if (seen < WARMUP) {
            return true; // allow hedging during warmup so we can learn
        }
        return currentHedgeRate() < policy.hedgeRateCap();
    }

    @Override
    public void recordCompletion(long latencyMs, boolean hedged) {
        int li = (int) (latencyCount.getAndIncrement() % LATENCY_WINDOW);
        synchronized (latencies) {
            latencies[li] = latencyMs;
        }
        int ri = (int) (rateCount.getAndIncrement() % RATE_WINDOW);
        synchronized (hedgedFlags) {
            hedgedFlags[ri] = hedged;
        }
        totalRequests.incrementAndGet();
        if (hedged) {
            totalHedged.incrementAndGet();
        }
    }

    // ---- introspection for the metrics endpoint ----

    public long p50() {
        return percentile(0.50);
    }

    public long p95() {
        return percentile(0.95);
    }

    public long p99() {
        return percentile(0.99);
    }

    public double lifetimeHedgeRate() {
        long t = totalRequests.get();
        return t == 0 ? 0 : (double) totalHedged.get() / t;
    }

    public long totalRequests() {
        return totalRequests.get();
    }

    public long totalHedged() {
        return totalHedged.get();
    }

    public double currentHedgeRate() {
        long seen = Math.min(rateCount.get(), RATE_WINDOW);
        if (seen == 0) {
            return 0;
        }
        int hedged = 0;
        synchronized (hedgedFlags) {
            for (int i = 0; i < seen; i++) {
                if (hedgedFlags[i]) {
                    hedged++;
                }
            }
        }
        return (double) hedged / seen;
    }

    private long percentile(double p) {
        long seen = Math.min(latencyCount.get(), LATENCY_WINDOW);
        if (seen == 0) {
            return 0;
        }
        long[] copy;
        synchronized (latencies) {
            copy = Arrays.copyOf(latencies, (int) seen);
        }
        Arrays.sort(copy);
        int idx = (int) Math.ceil(p * copy.length) - 1;
        return copy[Math.max(0, Math.min(idx, copy.length - 1))];
    }
}
