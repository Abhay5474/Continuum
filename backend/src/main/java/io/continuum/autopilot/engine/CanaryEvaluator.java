package io.continuum.autopilot.engine;

import io.continuum.autopilot.stats.BetaDistribution;
import org.springframework.stereotype.Component;

/**
 * Decides whether a canary should be promoted, rolled back, or kept running,
 * using Bayesian comparison of success rates plus latency/cost regression guards.
 *
 * Pure and deterministic — unit tested. Promotion requires the candidate to be
 * <em>confidently</em> at least as good (its 95% lower bound clears the baseline
 * mean); rollback triggers on a confident regression in success, latency or cost.
 */
@Component
public class CanaryEvaluator {

    private static final int MIN_SAMPLES = 20;
    private static final double LATENCY_REGRESSION = 1.5;
    private static final double COST_REGRESSION = 1.5;

    public enum Verdict { PROMOTE, ROLLBACK, CONTINUE }

    public record BundleStats(long successes, long failures, double avgLatencyMs, double avgCostUsd) {
        public long total() {
            return successes + failures;
        }
    }

    public record Result(Verdict verdict, String reason, double confidence) {
    }

    public Result evaluate(BundleStats candidate, BundleStats baseline) {
        if (candidate.total() < MIN_SAMPLES) {
            return new Result(Verdict.CONTINUE,
                    "Gathering canary data (" + candidate.total() + "/" + MIN_SAMPLES + ")", 0.0);
        }

        BetaDistribution cand = BetaDistribution.fromCounts(candidate.successes(), candidate.failures());
        BetaDistribution base = BetaDistribution.fromCounts(baseline.successes(), baseline.failures());

        // Hard regression guards first.
        if (baseline.avgLatencyMs() > 0 && candidate.avgLatencyMs() > baseline.avgLatencyMs() * LATENCY_REGRESSION) {
            return new Result(Verdict.ROLLBACK, "Latency regression: canary "
                    + Math.round(candidate.avgLatencyMs()) + "ms vs baseline "
                    + Math.round(baseline.avgLatencyMs()) + "ms", 0.9);
        }
        if (baseline.avgCostUsd() > 0 && candidate.avgCostUsd() > baseline.avgCostUsd() * COST_REGRESSION) {
            return new Result(Verdict.ROLLBACK, "Cost regression: canary "
                    + candidate.avgCostUsd() + " vs baseline " + baseline.avgCostUsd(), 0.9);
        }
        if (cand.upperBound95() < base.mean()) {
            return new Result(Verdict.ROLLBACK, "Success-rate regression: canary confidently worse ("
                    + pct(cand.mean()) + " vs " + pct(base.mean()) + ")", 0.9);
        }

        // Promote only when confidently at least as good.
        if (cand.lowerBound95() >= base.mean() && cand.mean() >= base.mean()) {
            double conf = Math.min(1.0, (cand.mean() - base.lowerBound95()) * 4 + 0.5);
            return new Result(Verdict.PROMOTE, "Canary confidently >= baseline ("
                    + pct(cand.mean()) + " vs " + pct(base.mean()) + ")", conf);
        }

        return new Result(Verdict.CONTINUE, "No confident difference yet ("
                + pct(cand.mean()) + " vs " + pct(base.mean()) + ")", 0.3);
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }
}
