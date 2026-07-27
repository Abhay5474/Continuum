package io.continuum.drift;

/**
 * Detects a sustained downward shift in a quality signal.
 *
 * <p>CUSUM (Page, 1954). The reason it is the right tool rather than a threshold
 * or a moving average: a provider that degrades does not usually fall off a
 * cliff. It gets slightly worse and stays slightly worse. A fixed threshold
 * misses that until the damage is large; a moving average absorbs it, because
 * the average is exactly what has moved.
 *
 * <p>CUSUM accumulates the shortfall against a baseline, so a small persistent
 * deficit adds up and a brief dip decays back to zero. Two parameters shape it:
 *
 * <ul>
 *   <li><b>Slack (k).</b> Deviations smaller than this are treated as noise and
 *       contribute nothing. Without slack, ordinary variance walks the
 *       accumulator upward and every provider eventually trips.</li>
 *   <li><b>Threshold (h).</b> How much accumulated shortfall constitutes drift.
 *       Larger means slower to fire and harder to fool.</li>
 * </ul>
 *
 * <p>Deliberately one-sided. A provider that gets <em>better</em> is not an
 * incident, and watching for improvement would only add false positives.
 *
 * <p>The baseline is learned, not configured, because "good" for one workload is
 * not "good" for another — a code-generation account and a summarisation account
 * have entirely different normal quality. Until the warm-up completes the
 * detector reports {@code WARMING} and never fires: declaring drift from four
 * observations would trip a breaker on noise.
 */
public class CusumDetector {

    /** How the detector currently sees the stream. */
    public enum State {
        /** Still learning what normal looks like. */
        WARMING,
        /** Within expected variation. */
        STABLE,
        /** Shortfall is accumulating but has not yet cleared the threshold. */
        DRIFTING,
        /** Sustained degradation confirmed. */
        DRIFTED
    }

    /**
     * Ceiling on what one observation may contribute.
     *
     * <p>Without it a single catastrophic answer contributes almost the whole
     * threshold and can trip the breaker on its own — which is precisely the
     * outlier sensitivity CUSUM is chosen to avoid. Capping the per-observation
     * shortfall makes the detector respond to <em>persistence</em>: one terrible
     * answer is noise, several in a row are a pattern.
     */
    private static final double MAX_CONTRIBUTION = 0.25;

    private final int warmup;
    private final double slack;
    private final double threshold;

    private long count;
    private double mean;
    /** Welford's M2, for a running variance that does not need the whole series. */
    private double m2;
    private double baseline = Double.NaN;
    private double sum;
    private double peak;

    /**
     * @param warmup    observations needed before a baseline is trusted
     * @param slack     deviation below the baseline treated as noise, in units of the signal
     * @param threshold accumulated shortfall that means drift
     */
    public CusumDetector(int warmup, double slack, double threshold) {
        this.warmup = Math.max(5, warmup);
        this.slack = Math.max(0, slack);
        this.threshold = Math.max(0.0001, threshold);
    }

    /** Sensible defaults for a signal in [0,1]. */
    public static CusumDetector forUnitSignal() {
        // 0.05 slack absorbs ordinary variation in a quality score; 0.75
        // accumulated shortfall is roughly "15 consecutive observations a tenth
        // below baseline", which is a pattern and not an accident.
        return new CusumDetector(30, 0.05, 0.75);
    }

    /**
     * Records one observation and returns the state after it.
     *
     * <p>During warm-up the observation feeds the baseline. Afterwards the
     * baseline is frozen, because continuing to update it with degraded data is
     * how a monitor learns to accept the degradation.
     */
    public State observe(double value) {
        count++;
        // Welford: numerically stable running mean and variance.
        double delta = value - mean;
        mean += delta / count;
        m2 += delta * (value - mean);

        if (count < warmup) {
            return State.WARMING;
        }
        if (Double.isNaN(baseline)) {
            baseline = mean;
        }

        double shortfall = Math.min(MAX_CONTRIBUTION, baseline - value - slack);
        sum = Math.max(0, sum + shortfall);
        peak = Math.max(peak, sum);

        if (sum >= threshold) {
            return State.DRIFTED;
        }
        return sum > 0 ? State.DRIFTING : State.STABLE;
    }

    /** Clears the accumulator without forgetting the baseline — used on recovery. */
    public void reset() {
        sum = 0;
    }

    /** Forgets everything, including the baseline. Used when re-learning a model. */
    public void rebaseline() {
        count = 0;
        mean = 0;
        m2 = 0;
        baseline = Double.NaN;
        sum = 0;
        peak = 0;
    }

    public long count() {
        return count;
    }

    public double mean() {
        return mean;
    }

    public double baseline() {
        return baseline;
    }

    /** Accumulated shortfall right now. */
    public double accumulated() {
        return sum;
    }

    /** How close the accumulator is to firing, in [0,1] — the console draws this. */
    public double pressure() {
        return Math.max(0, Math.min(1, sum / threshold));
    }

    public double threshold() {
        return threshold;
    }

    public double stdDev() {
        return count < 2 ? 0 : Math.sqrt(m2 / (count - 1));
    }

    public boolean warm() {
        return count >= warmup;
    }
}
