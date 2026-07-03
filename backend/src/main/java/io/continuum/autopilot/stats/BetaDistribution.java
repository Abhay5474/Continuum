package io.continuum.autopilot.stats;

import java.util.Random;

/**
 * Minimal Beta(α, β) distribution: posterior over a success probability given
 * α−1 observed successes and β−1 failures. Used for Thompson sampling (draw a
 * plausible success rate per arm) and for Bayesian credible intervals.
 *
 * Sampling uses the Gamma-ratio method (Beta = G(α)/(G(α)+G(β))), with
 * Marsaglia–Tsang for Gamma. Pure and deterministic given a seeded RNG, so the
 * decision logic is unit-testable.
 */
public final class BetaDistribution {

    private final double alpha;
    private final double beta;

    public BetaDistribution(double alpha, double beta) {
        this.alpha = Math.max(1e-6, alpha);
        this.beta = Math.max(1e-6, beta);
    }

    /** Beta from observed successes/failures with a uniform Beta(1,1) prior. */
    public static BetaDistribution fromCounts(long successes, long failures) {
        return new BetaDistribution(successes + 1.0, failures + 1.0);
    }

    public double mean() {
        return alpha / (alpha + beta);
    }

    public double variance() {
        double s = alpha + beta;
        return (alpha * beta) / (s * s * (s + 1.0));
    }

    /** Standard-deviation-based ~95% lower credible bound (clamped to [0,1]). */
    public double lowerBound95() {
        return Math.max(0.0, mean() - 1.96 * Math.sqrt(variance()));
    }

    public double upperBound95() {
        return Math.min(1.0, mean() + 1.96 * Math.sqrt(variance()));
    }

    public double sample(Random rng) {
        double x = gamma(alpha, rng);
        double y = gamma(beta, rng);
        return x / (x + y);
    }

    private static double gamma(double shape, Random rng) {
        if (shape < 1.0) {
            double u = rng.nextDouble();
            return gamma(shape + 1.0, rng) * Math.pow(u, 1.0 / shape);
        }
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x = rng.nextGaussian();
            double v = 1.0 + c * x;
            if (v <= 0) {
                continue;
            }
            v = v * v * v;
            double u = rng.nextDouble();
            if (u < 1.0 - 0.0331 * x * x * x * x) {
                return d * v;
            }
            if (Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) {
                return d * v;
            }
        }
    }

    public double alpha() {
        return alpha;
    }

    public double beta() {
        return beta;
    }
}
