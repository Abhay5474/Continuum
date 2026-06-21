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

    public ActivityFailedException(String activityType, String message) {
        super("Activity '" + activityType + "' failed permanently: " + message);
        this.activityType = activityType;
    }

    public String getActivityType() {
        return activityType;
    }
}
