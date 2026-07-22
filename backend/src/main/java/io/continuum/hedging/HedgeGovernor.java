package io.continuum.hedging;

/**
 * The adaptive control loop around hedging (V8 — <em>The Tail at Scale</em>).
 *
 * Separated from the executor so the executor stays a pure, deterministically
 * testable race, while the governor owns the live latency distribution and the
 * rate cap. A {@code null} governor makes the executor behave exactly as the
 * original fixed-threshold implementation.
 */
public interface HedgeGovernor {

    /**
     * The effective hedge delay for this request. Adaptive policies return the
     * live p95 (floored by {@code minThresholdMs}); non-adaptive policies return
     * the fixed {@code thresholdMs}.
     */
    long effectiveThresholdMs(HedgingPolicy policy);

    /**
     * Whether a hedge may be launched right now, given the running hedge rate vs
     * the policy cap. Bounds the extra load to a few percent.
     */
    boolean allowHedge(HedgingPolicy policy);

    /** Record a completed request's total latency and whether it actually hedged. */
    void recordCompletion(long latencyMs, boolean hedged);
}
