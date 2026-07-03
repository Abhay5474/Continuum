package io.continuum.autopilot.engine;

import io.continuum.autopilot.engine.CanaryEvaluator.BundleStats;
import io.continuum.autopilot.engine.CanaryEvaluator.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CanaryEvaluatorTest {

    private final CanaryEvaluator evaluator = new CanaryEvaluator();

    @Test
    void continuesUntilEnoughData() {
        var r = evaluator.evaluate(new BundleStats(3, 1, 500, 0.001), new BundleStats(900, 100, 500, 0.001));
        assertEquals(Verdict.CONTINUE, r.verdict());
    }

    @Test
    void promotesWhenConfidentlyBetter() {
        // candidate 98% over 200, baseline 80% over 1000
        var r = evaluator.evaluate(new BundleStats(196, 4, 500, 0.001), new BundleStats(800, 200, 500, 0.001));
        assertEquals(Verdict.PROMOTE, r.verdict(), r.reason());
    }

    @Test
    void rollsBackOnSuccessRegression() {
        // candidate 50% over 200, baseline 90% over 1000
        var r = evaluator.evaluate(new BundleStats(100, 100, 500, 0.001), new BundleStats(900, 100, 500, 0.001));
        assertEquals(Verdict.ROLLBACK, r.verdict(), r.reason());
    }

    @Test
    void rollsBackOnLatencyRegression() {
        var r = evaluator.evaluate(new BundleStats(190, 10, 3000, 0.001), new BundleStats(900, 100, 800, 0.001));
        assertEquals(Verdict.ROLLBACK, r.verdict(), r.reason());
        assertTrue(r.reason().toLowerCase().contains("latency"));
    }

    @Test
    void rollsBackOnCostRegression() {
        var r = evaluator.evaluate(new BundleStats(190, 10, 800, 0.01), new BundleStats(900, 100, 800, 0.002));
        assertEquals(Verdict.ROLLBACK, r.verdict(), r.reason());
        assertTrue(r.reason().toLowerCase().contains("cost"));
    }
}
