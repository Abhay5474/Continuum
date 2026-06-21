package io.continuum.routing;

/** A provider's per-dimension and total score for one routing decision. */
public record ProviderScore(
        String provider,
        double totalScore,
        double costScore,
        double latencyScore,
        double qualityScore,
        double predictedCostUsd,
        double avgLatencyMs,
        double quality,
        double qualityConfidence,
        boolean disqualified,
        String notes) {
}
