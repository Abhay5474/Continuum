package io.continuum.autopilot.model;

import java.util.List;

/**
 * The immutable, bounded set of policy knobs Autopilot is allowed to tune. Every
 * field maps to an existing runtime surface — Autopilot never touches business
 * logic, provider internals or application code, only these safe dials.
 *
 * Stored as JSON inside a policy-bundle row; each change produces a new version.
 */
public record PolicyBundle(
        String routingMode,                 // BALANCED | LOW_COST | LOW_LATENCY | HIGH_QUALITY
        List<String> providerOrder,         // preferred provider ordering
        int maxRetries,                     // per-activity retry cap
        long hedgeThresholdMs,              // tail-latency hedge trigger
        int hedgeMaxParallel,               // max parallel hedges
        double semanticDriftThreshold,      // drift beyond this triggers rollback/recommendation
        int canaryPercentage,               // % of traffic a candidate receives
        double costCapUsd,                  // per-request budget
        long latencyCapMs,                  // per-request latency budget
        int memoryCompressionThreshold,     // episodic memories before compression
        double verificationPassThreshold,   // semantic replay pass bar
        int timeoutSeconds) {               // activity timeout

    /** Beginner-friendly, conservative starting policy derived from a profile. */
    public static PolicyBundle defaultFor(AutopilotMode mode, List<String> allowedProviders,
                                          double maxCost, long maxLatency) {
        return new PolicyBundle(
                switch (mode) {
                    case LOW_COST -> "LOW_COST";
                    case LOW_LATENCY -> "LOW_LATENCY";
                    case HIGH_QUALITY, SAFETY_FIRST -> "HIGH_QUALITY";
                    case BALANCED -> "BALANCED";
                },
                allowedProviders == null || allowedProviders.isEmpty()
                        ? List.of("gemini", "groq", "mock") : allowedProviders,
                3,
                mode == AutopilotMode.LOW_LATENCY ? 500 : 900,
                1,
                0.25,
                10,
                maxCost > 0 ? maxCost : 0.02,
                maxLatency > 0 ? maxLatency : 5000,
                10,
                0.65,
                mode == AutopilotMode.LOW_LATENCY ? 20 : 45);
    }

    public PolicyBundle withProviderOrder(List<String> order) {
        return new PolicyBundle(routingMode, order, maxRetries, hedgeThresholdMs, hedgeMaxParallel,
                semanticDriftThreshold, canaryPercentage, costCapUsd, latencyCapMs,
                memoryCompressionThreshold, verificationPassThreshold, timeoutSeconds);
    }

    public PolicyBundle withRoutingMode(String mode) {
        return new PolicyBundle(mode, providerOrder, maxRetries, hedgeThresholdMs, hedgeMaxParallel,
                semanticDriftThreshold, canaryPercentage, costCapUsd, latencyCapMs,
                memoryCompressionThreshold, verificationPassThreshold, timeoutSeconds);
    }

    public PolicyBundle withHedgeThresholdMs(long ms) {
        return new PolicyBundle(routingMode, providerOrder, maxRetries, ms, hedgeMaxParallel,
                semanticDriftThreshold, canaryPercentage, costCapUsd, latencyCapMs,
                memoryCompressionThreshold, verificationPassThreshold, timeoutSeconds);
    }
}
