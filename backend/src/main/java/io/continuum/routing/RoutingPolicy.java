package io.continuum.routing;

/**
 * Constraints and objective for a routing decision. For {@link RoutingMode#CUSTOM}
 * the explicit weights are used; otherwise the mode implies them.
 */
public record RoutingPolicy(
        RoutingMode mode,
        double wCost,
        double wLatency,
        double wQuality,
        Double maxBudgetUsd,      // null = no budget cap
        Long requiredLatencyMs,   // null = no latency SLO
        Double requiredQuality) { // null = no quality floor

    public static RoutingPolicy of(RoutingMode mode) {
        return switch (mode) {
            case LOW_COST -> new RoutingPolicy(mode, 0.7, 0.2, 0.1, null, null, null);
            case LOW_LATENCY -> new RoutingPolicy(mode, 0.1, 0.7, 0.2, null, null, null);
            case HIGH_QUALITY -> new RoutingPolicy(mode, 0.1, 0.2, 0.7, null, null, null);
            case BALANCED, CUSTOM -> new RoutingPolicy(mode, 0.34, 0.33, 0.33, null, null, null);
        };
    }
}
