package io.continuum.persistence.entity;

public enum TaskStatus {
    /** Available to be claimed by a worker once {@code visibleAt} has passed. */
    PENDING,
    /** Claimed by a worker; protected by a visibility timeout ({@code lockedUntil}). */
    RUNNING,
    /** Finished successfully. */
    COMPLETED,
    /** Failed and will not be retried further (terminal for the task). */
    FAILED
}
