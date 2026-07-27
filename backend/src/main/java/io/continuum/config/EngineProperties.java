package io.continuum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables for the engine's pollers, exposed under {@code continuum.engine.*}. */
@ConfigurationProperties(prefix = "continuum.engine")
public class EngineProperties {

    /** How often (ms) workers poll their queues. */
    private long pollIntervalMs = 500;

    /** Max tasks claimed per poll tick. */
    private int batchSize = 10;

    /** How often (ms) the recovery sweeper reclaims abandoned tasks. */
    private long recoveryIntervalMs = 5000;

    /** Visibility timeout (s) granted to a claimed workflow decision task. */
    private int workflowTaskTimeoutSeconds = 60;

    /** Whether the embedded worker pollers are enabled in this process. */
    private boolean workersEnabled = true;

    /**
     * How many activities this process may execute concurrently.
     *
     * <p>Activities used to run inline on the scheduler thread, one after
     * another, which meant a single slow HTTP step blocked every workflow
     * decision, the outbox dispatcher and the recovery sweeper behind it — and
     * made {@code executeActivitiesParallel} parallel in name only. They now run
     * on a bounded pool, and the poller claims no more work than the pool has
     * room for, so the queue stays available to other workers.
     */
    private int activityConcurrency = 16;

    public int getActivityConcurrency() {
        return activityConcurrency;
    }

    public void setActivityConcurrency(int activityConcurrency) {
        this.activityConcurrency = activityConcurrency;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getRecoveryIntervalMs() {
        return recoveryIntervalMs;
    }

    public void setRecoveryIntervalMs(long recoveryIntervalMs) {
        this.recoveryIntervalMs = recoveryIntervalMs;
    }

    public int getWorkflowTaskTimeoutSeconds() {
        return workflowTaskTimeoutSeconds;
    }

    public void setWorkflowTaskTimeoutSeconds(int workflowTaskTimeoutSeconds) {
        this.workflowTaskTimeoutSeconds = workflowTaskTimeoutSeconds;
    }

    public boolean isWorkersEnabled() {
        return workersEnabled;
    }

    public void setWorkersEnabled(boolean workersEnabled) {
        this.workersEnabled = workersEnabled;
    }
}
