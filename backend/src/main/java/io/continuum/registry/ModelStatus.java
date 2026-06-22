package io.continuum.registry;

/**
 * Model lifecycle states. Continuum never blindly switches to a newly-discovered
 * model: it flows DISCOVERED → TESTING → ACTIVE, and ages out via DEPRECATED →
 * REMOVED. Only ACTIVE models are eligible for routing.
 */
public enum ModelStatus {
    DISCOVERED,
    TESTING,
    ACTIVE,
    DEPRECATED,
    REMOVED
}
