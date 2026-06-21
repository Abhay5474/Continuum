package io.continuum.core.workflow;

/**
 * Per-activity execution policy chosen by the workflow when it schedules work.
 */
public class ActivityOptions {

    private int maxAttempts = 3;
    private int timeoutSeconds = 30;

    public static ActivityOptions defaults() {
        return new ActivityOptions();
    }

    public ActivityOptions maxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
        return this;
    }

    public ActivityOptions timeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        return this;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }
}
