package io.continuum.hedging;

/**
 * Controls tail-latency hedging: when a primary request is taking too long,
 * launch a parallel request to the next provider and take the first success.
 */
public record HedgingPolicy(
        long thresholdMs,        // launch a hedge if no response within this
        int maxHedges,           // max additional in-flight requests beyond the primary
        Double perRequestBudgetUsd) { // null = no budget cap on hedging

    public static HedgingPolicy defaults() {
        return new HedgingPolicy(800, 1, null);
    }
}
