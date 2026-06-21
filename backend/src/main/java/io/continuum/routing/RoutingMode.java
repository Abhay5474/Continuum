package io.continuum.routing;

/** Optimization objective for provider selection. */
public enum RoutingMode {
    LOW_COST,
    LOW_LATENCY,
    HIGH_QUALITY,
    BALANCED,
    CUSTOM
}
