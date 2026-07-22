package io.continuum.hedging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The adaptive hedging control loop (V8 — The Tail at Scale): the hedge trigger
 * tracks the live p95, and the rate cap bounds the fraction of requests that
 * hedge to a few percent.
 */
class AdaptiveHedgeGovernorTest {

    private final HedgingPolicy adaptive = HedgingPolicy.adaptiveDefaults(); // adaptive, cap 5%, floor 50ms

    @Test
    void warmupFallsBackToTheFixedThreshold() {
        AdaptiveHedgeGovernor g = new AdaptiveHedgeGovernor();
        // Before enough samples, use the configured threshold (no wild early p95).
        assertEquals(adaptive.thresholdMs(), g.effectiveThresholdMs(adaptive));
    }

    @Test
    void thresholdTracksTheLiveP95() {
        AdaptiveHedgeGovernor g = new AdaptiveHedgeGovernor();
        // 95 fast requests, 5 very slow — p95 sits near the fast band, well below the slow tail.
        for (int i = 0; i < 95; i++) {
            g.recordCompletion(100, false);
        }
        for (int i = 0; i < 5; i++) {
            g.recordCompletion(5000, false);
        }
        long threshold = g.effectiveThresholdMs(adaptive);
        assertTrue(threshold >= 100 && threshold <= 5000,
                "adaptive threshold sits at the p95, got " + threshold);
        assertTrue(threshold < 5000, "the threshold must be below the slow tail so those requests hedge");
        assertEquals(100, g.p50());
    }

    @Test
    void thresholdIsFlooredByMinThreshold() {
        AdaptiveHedgeGovernor g = new AdaptiveHedgeGovernor();
        for (int i = 0; i < 100; i++) {
            g.recordCompletion(5, false); // everything blazing fast → raw p95 ~5ms
        }
        long threshold = g.effectiveThresholdMs(adaptive);
        assertEquals(adaptive.minThresholdMs(), threshold,
                "never hedge sooner than the floor, even if p95 is tiny");
    }

    @Test
    void rateCapBlocksHedgingOnceExceeded() {
        AdaptiveHedgeGovernor g = new AdaptiveHedgeGovernor();
        // Drive the observed hedge rate well above the 5% cap.
        for (int i = 0; i < 100; i++) {
            g.recordCompletion(200, true); // every request hedged → 100% rate
        }
        assertFalse(g.allowHedge(adaptive), "hedge rate above the cap must block further hedging");
        assertTrue(g.currentHedgeRate() > adaptive.hedgeRateCap());
    }

    @Test
    void rateCapAllowsHedgingWhenBelowCap() {
        AdaptiveHedgeGovernor g = new AdaptiveHedgeGovernor();
        // 2 hedges out of 100 = 2%, under the 5% cap.
        for (int i = 0; i < 98; i++) {
            g.recordCompletion(200, false);
        }
        for (int i = 0; i < 2; i++) {
            g.recordCompletion(200, true);
        }
        assertTrue(g.allowHedge(adaptive), "under the cap, hedging is allowed");
        assertTrue(g.currentHedgeRate() < adaptive.hedgeRateCap());
    }

    @Test
    void nonAdaptivePolicyIgnoresTheGovernorStats() {
        AdaptiveHedgeGovernor g = new AdaptiveHedgeGovernor();
        for (int i = 0; i < 100; i++) {
            g.recordCompletion(9999, false);
        }
        HedgingPolicy fixed = new HedgingPolicy(300, 1, null); // legacy ctor: adaptive off, cap 1.0
        assertEquals(300, g.effectiveThresholdMs(fixed), "fixed policy uses its configured threshold");
        assertTrue(g.allowHedge(fixed), "no cap on the legacy policy");
    }
}
