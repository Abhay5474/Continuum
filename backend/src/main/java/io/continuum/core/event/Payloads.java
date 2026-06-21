package io.continuum.core.event;

/**
 * Strongly-typed bodies for each {@link EventType}, serialized to JSON in the
 * event log. The {@code commandSeq} field links scheduling/completion/failure
 * events for the same logical workflow command across replays.
 */
public final class Payloads {

    private Payloads() {
    }

    public record WorkflowStarted(String workflowType, String input) {
    }

    public record ActivityScheduled(long commandSeq, String activityType, String input,
                                    int maxAttempts, int timeoutSeconds, String idempotencyKey) {
    }

    public record ActivityStarted(long commandSeq, String activityType, int attempt, String workerId) {
    }

    public record ActivityCompleted(long commandSeq, String activityType, String result) {
    }

    public record ActivityFailed(long commandSeq, String activityType, String error, int attempt, boolean terminal) {
    }

    public record RetryScheduled(long commandSeq, String activityType, int nextAttempt, String visibleAt) {
    }

    public record SideEffectRecorded(long commandSeq, String value) {
    }

    public record WorkflowCompleted(String result) {
    }

    public record WorkflowFailed(String error) {
    }
}
