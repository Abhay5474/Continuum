package io.continuum.core.workflow;

/**
 * Per-activity execution policy chosen by the workflow when it schedules work.
 */
public class ActivityOptions {

    private int maxAttempts = 3;
    private int timeoutSeconds = 30;
    private int delaySeconds = 0;

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

    /**
     * Hold the task invisible for this long before a worker may claim it.
     *
     * This is how a durable timer is expressed: the wait lives as a row with a
     * future visibility, so no worker thread is blocked and the timer survives a
     * restart like any other task.
     */
    public ActivityOptions delaySeconds(int delaySeconds) {
        this.delaySeconds = Math.max(0, delaySeconds);
        return this;
    }

    public int getDelaySeconds() {
        return delaySeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }
}
