package io.continuum.core.workflow;

/**
 * Raised inside workflow code when an activity has permanently failed (all
 * retries — and, for LLM activities, all provider fallbacks — exhausted).
 *
 * Workflow authors may catch this to implement compensation or alternative
 * branches. If it propagates out of the workflow, the engine records
 * {@code WORKFLOW_FAILED}.
 */
public class ActivityFailedException extends RuntimeException {

    private final String activityType;
    /** The activity's own error, without the "Activity ... failed permanently" framing. */
    private final String cause;
    /** Its position among the calls of a parallel batch; -1 for a single call. */
    private final int callIndex;

    public ActivityFailedException(String activityType, String message) {
        this(activityType, message, -1);
    }

    public ActivityFailedException(String activityType, String message, int callIndex) {
        this(activityType, message, callIndex, "Activity '" + activityType + "' failed permanently: " + message);
    }

    private ActivityFailedException(String activityType, String cause, int callIndex, String full) {
        super(full);
        this.activityType = activityType;
        this.cause = cause;
        this.callIndex = callIndex;
    }

    /** The same failure, told in the workflow's own terms (a step name rather than an activity type). */
    public ActivityFailedException describedAs(String message) {
        return new ActivityFailedException(activityType, cause, callIndex, message);
    }

    public String getFailure() {
        return cause;
    }

    public int getCallIndex() {
        return callIndex;
    }

    public String getActivityType() {
        return activityType;
    }
}
