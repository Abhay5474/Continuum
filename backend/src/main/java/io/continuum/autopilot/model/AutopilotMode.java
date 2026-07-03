package io.continuum.autopilot.model;

/**
 * The developer's high-level optimization objective for Autopilot. Determines
 * the weights the decision engine puts on cost, latency and quality when it
 * proposes policy changes.
 */
public enum AutopilotMode {
    BALANCED(0.34, 0.33, 0.33),
    LOW_COST(0.7, 0.2, 0.1),
    LOW_LATENCY(0.15, 0.7, 0.15),
    HIGH_QUALITY(0.1, 0.2, 0.7),
    SAFETY_FIRST(0.15, 0.15, 0.7);

    public final double wCost;
    public final double wLatency;
    public final double wQuality;

    AutopilotMode(double wCost, double wLatency, double wQuality) {
        this.wCost = wCost;
        this.wLatency = wLatency;
        this.wQuality = wQuality;
    }
}
