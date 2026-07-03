package io.continuum.autopilot.stats;

/**
 * Online mean/variance (Welford's algorithm) for streaming latency/cost samples
 * without retaining the raw data. Numerically stable and O(1) per update.
 */
public final class WelfordStats {

    private long count;
    private double mean;
    private double m2;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;

    public void add(double x) {
        count++;
        double delta = x - mean;
        mean += delta / count;
        m2 += delta * (x - mean);
        min = Math.min(min, x);
        max = Math.max(max, x);
    }

    public long count() {
        return count;
    }

    public double mean() {
        return count == 0 ? 0.0 : mean;
    }

    public double variance() {
        return count < 2 ? 0.0 : m2 / (count - 1);
    }

    public double stddev() {
        return Math.sqrt(variance());
    }

    public double min() {
        return count == 0 ? 0.0 : min;
    }

    public double max() {
        return count == 0 ? 0.0 : max;
    }

    /** Normal-approximation percentile (e.g. p95 via z≈1.645). */
    public double approxPercentile(double z) {
        return mean() + z * stddev();
    }
}
