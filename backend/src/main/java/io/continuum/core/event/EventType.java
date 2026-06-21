package io.continuum.core.event;

/**
 * The complete vocabulary of facts that can be recorded about a workflow.
 *
 * The event log is the single source of truth. Workflow state is never stored
 * directly — it is always reconstructed by folding these events in order.
 */
public enum EventType {
    /** A new workflow execution was created. Always the first event. */
    WORKFLOW_STARTED,

    /** The (deterministic) workflow code decided to run an activity. */
    ACTIVITY_SCHEDULED,

    /** A worker claimed the activity task and began executing it. */
    ACTIVITY_STARTED,

    /** An activity finished successfully. Carries the recorded result. */
    ACTIVITY_COMPLETED,

    /** An activity attempt failed (may still be retried). */
    ACTIVITY_FAILED,

    /** A failed activity will be retried after a backoff delay. */
    RETRY_SCHEDULED,

    /**
     * A non-deterministic value (clock read, random, uuid) produced by the
     * workflow itself was captured so that replay yields the same value.
     */
    SIDE_EFFECT_RECORDED,

    /** A durable timer fired. */
    TIMER_FIRED,

    /** The workflow ran to completion. Terminal. */
    WORKFLOW_COMPLETED,

    /** The workflow failed permanently. Terminal. */
    WORKFLOW_FAILED
}
