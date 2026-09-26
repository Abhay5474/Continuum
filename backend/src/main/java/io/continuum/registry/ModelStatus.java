package io.continuum.registry;

/**
 * Model lifecycle states. Continuum never blindly switches to a newly-discovered
 * model: it flows DISCOVERED → (a test call) → ACTIVE, and ages out via
 * DEPRECATED (missing from one successful list) → REMOVED (missing from two, or
 * missing and reported gone by the provider). Only ACTIVE models are eligible
 * for routing.
 */
public enum ModelStatus {
    DISCOVERED,
    TESTING,
    ACTIVE,
    DEPRECATED,
    REMOVED,
    /**
     * Listed by the provider, but this deployment's key cannot use it: no free
     * quota, no access, or it refused a minimal chat request. Not routed.
     * Tested again after a while, since free tiers change.
     */
    UNAVAILABLE
}
