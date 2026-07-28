package io.continuum.uncertainty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides when enough samples have been drawn.
 *
 * <p>Measuring confidence by resampling costs k times the tokens, and k is
 * configured once for every request a tenant makes. That is the wrong shape:
 * <i>"what time do you close?"</i> and <i>"does this indemnity clause survive
 * termination?"</i> do not need the same evidence. The first is decided after two
 * samples that say the same thing; the second is not decided after five.
 *
 * <p>This is <b>Adaptive-Consistency</b> (Aggarwal, Yang, Mausam &amp; Roy,
 * EMNLP 2023): keep a Beta posterior over the gap between the top two answer
 * clusters and stop as soon as the probability of the majority being overturned
 * by further sampling falls below a threshold. They report ~7.9× fewer samples
 * for under 0.1% accuracy loss. Underneath it is Wald's sequential probability
 * ratio test (1945) — stop when the evidence is decisive, not when a counter
 * runs out.
 *
 * <p>The posterior used here is the Beta over the majority cluster's share.
 * With {@code a} answers in the leading cluster and {@code b} in the rest,
 * Beta(a+1, b+1) is the belief about the true majority rate, and the quantity
 * that matters is P(rate &lt; 0.5) — the chance the leader is not really the
 * leader. When that drops below the threshold, more samples cannot reasonably
 * change the answer and buying them is buying nothing.
 *
 * <p><b>What this does not do.</b> It stops early when answers <em>agree</em>.
 * It cannot shorten a genuinely contested question — and should not: disagreement
 * is exactly the case where the extra samples are the point. The saving comes
 * from the easy majority of traffic, which is where it should come from.
 */
public final class AdaptiveStopping {

    /** Never stop before this many; two identical answers is not evidence. */
    public static final int MIN_SAMPLES = 2;

    private AdaptiveStopping() {
    }

    /**
     * @param stop      whether to stop sampling now
     * @param overturn  P(the leading cluster is not the true majority)
     * @param reason    written for the person reading the trace
     */
    public record Decision(boolean stop, double overturn, int drawn, int leader, String reason) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stop", stop);
            m.put("overturnProbability", Math.round(overturn * 10000) / 10000.0);
            m.put("drawn", drawn);
            m.put("leader", leader);
            m.put("reason", reason);
            return m;
        }
    }

    /**
     * Should sampling stop, given the clusters seen so far?
     *
     * @param clusterSizes how many answers fell into each meaning-cluster
     * @param drawn        samples taken so far
     * @param budget       the configured maximum
     * @param threshold    stop when the overturn probability is below this
     */
    public static Decision decide(List<Integer> clusterSizes, int drawn, int budget,
                                  double threshold) {
        if (drawn >= budget) {
            return new Decision(true, Double.NaN, drawn, leaderOf(clusterSizes),
                    "the configured sample budget is spent");
        }
        if (drawn < MIN_SAMPLES) {
            return new Decision(false, Double.NaN, drawn, leaderOf(clusterSizes),
                    "too few samples to judge anything yet");
        }

        int leader = leaderOf(clusterSizes);
        int rest = drawn - leader;
        double overturn = probabilityLeaderIsNotMajority(leader, rest);

        if (overturn <= threshold) {
            return new Decision(true, overturn, drawn, leader, String.format(
                    "%d of %d samples agree — a %.1f%% chance more sampling would change the "
                            + "answer, below the %.0f%% threshold", leader, drawn,
                    overturn * 100, threshold * 100));
        }
        return new Decision(false, overturn, drawn, leader, String.format(
                "%d of %d samples agree — still a %.1f%% chance more sampling would change the "
                        + "answer", leader, drawn, overturn * 100));
    }

    private static int leaderOf(List<Integer> sizes) {
        return sizes == null || sizes.isEmpty() ? 0
                : sizes.stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /**
     * P(p &lt; 0.5) under Beta(leader+1, rest+1) — the chance the apparent
     * majority is an artefact of small numbers.
     *
     * <p>Computed as the regularised incomplete beta function by the standard
     * continued-fraction expansion. Exact enough at these tiny integer
     * parameters, and cheap; the alternative — sampling the posterior — would
     * spend more compute deciding whether to spend compute.
     */
    static double probabilityLeaderIsNotMajority(int leader, int rest) {
        return regularisedIncompleteBeta(0.5, leader + 1, rest + 1);
    }

    // --- Beta CDF -------------------------------------------------------------

    private static double regularisedIncompleteBeta(double x, double a, double b) {
        if (x <= 0) {
            return 0;
        }
        if (x >= 1) {
            return 1;
        }
        double lbeta = logGamma(a + b) - logGamma(a) - logGamma(b)
                + a * Math.log(x) + b * Math.log1p(-x);
        // The continued fraction converges quickly on the side where x is small
        // relative to the mean; the symmetry identity handles the other side.
        if (x < (a + 1) / (a + b + 2)) {
            return Math.exp(lbeta) * betaContinuedFraction(x, a, b) / a;
        }
        return 1 - Math.exp(lbeta) * betaContinuedFraction(1 - x, b, a) / b;
    }

    private static double betaContinuedFraction(double x, double a, double b) {
        final double tiny = 1e-30;
        double qab = a + b;
        double qap = a + 1;
        double qam = a - 1;
        double c = 1;
        double d = 1 - qab * x / qap;
        if (Math.abs(d) < tiny) {
            d = tiny;
        }
        d = 1 / d;
        double h = d;
        for (int m = 1; m <= 200; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1 + aa / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1 / d;
            h *= d * c;

            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1 + aa / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < 3e-7) {
                break;
            }
        }
        return h;
    }

    /** Lanczos approximation; the parameters here are small integers. */
    private static double logGamma(double x) {
        double[] c = {76.18009172947146, -86.50532032941677, 24.01409824083091,
                -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5};
        double y = x;
        double tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (int j = 0; j < 6; j++) {
            ser += c[j] / ++y;
        }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }
}
