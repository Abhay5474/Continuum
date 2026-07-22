package io.continuum.hedging;

/**
 * Controls tail-latency hedging: when a primary request is taking too long,
 * launch a parallel request to the next provider and take the first success.
 *
 * <p>V8 research upgrade — faithful to Dean &amp; Barroso, <em>The Tail at
 * Scale</em> (CACM 2013):
 * <ul>
 *   <li><b>adaptive</b>: instead of a fixed delay, the hedge fires at the live
 *       observed p95 latency, so — by construction — only ~5% of requests ever
 *       hedge (the paper's key insight).</li>
 *   <li><b>hedgeRateCap</b>: a hard ceiling on the fraction of requests allowed
 *       to hedge, bounding the extra load exactly as the paper recommends.</li>
 *   <li><b>tied requests</b>: the moment one replica returns a usable answer the
 *       siblings are cancelled — already enforced by the executor.</li>
 * </ul>
 * The legacy 3-arg constructor preserves the original fixed-threshold behaviour
 * (adaptive off, no rate cap), so existing callers and tests are unchanged.
 */
public record HedgingPolicy(
        long thresholdMs,           // fixed hedge delay (used when adaptive is off)
        int maxHedges,              // max additional in-flight requests beyond the primary
        Double perRequestBudgetUsd, // null = no budget cap on hedging
        boolean adaptive,           // fire the hedge at the live p95 instead of thresholdMs
        double hedgeRateCap,        // max fraction of requests allowed to hedge (e.g. 0.05)
        long minThresholdMs) {      // floor for the adaptive threshold (never hedge sooner)

    /** Legacy constructor — fixed threshold, no adaptive behaviour, no rate cap. */
    public HedgingPolicy(long thresholdMs, int maxHedges, Double perRequestBudgetUsd) {
        this(thresholdMs, maxHedges, perRequestBudgetUsd, false, 1.0, 0);
    }

    public static HedgingPolicy defaults() {
        return new HedgingPolicy(800, 1, null);
    }

    /** Paper-faithful default: adaptive p95 trigger, capped at 5% hedge rate. */
    public static HedgingPolicy adaptiveDefaults() {
        return new HedgingPolicy(800, 1, null, true, 0.05, 50);
    }

    public HedgingPolicy withAdaptive(boolean value) {
        return new HedgingPolicy(thresholdMs, maxHedges, perRequestBudgetUsd, value, hedgeRateCap, minThresholdMs);
    }
}
