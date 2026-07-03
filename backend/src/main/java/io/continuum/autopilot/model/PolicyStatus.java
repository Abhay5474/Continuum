package io.continuum.autopilot.model;

/**
 * Lifecycle of a policy bundle. A bundle's <em>content</em> is immutable once
 * created; only its status transitions.
 *
 * CANDIDATE → (verified) → CANARY → (promoted) → ACTIVE
 *                                  ↘ (regression) → ROLLED_BACK
 * ACTIVE → (superseded) → ARCHIVED
 */
public enum PolicyStatus {
    CANDIDATE,
    CANARY,
    ACTIVE,
    ROLLED_BACK,
    ARCHIVED
}
