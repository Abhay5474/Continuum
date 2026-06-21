package io.continuum.core.workflow;

import io.continuum.common.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The deterministic surface a workflow uses to interact with the outside world.
 *
 * Every call here is keyed by a monotonically increasing <em>command sequence</em>
 * that is stable across replays (because workflow code is deterministic). On the
 * first run a command is recorded in history; on every subsequent replay the
 * recorded value is returned instead of re-executing — this is what makes
 * recovery safe and prevents workflow divergence.
 *
 * Rules for workflow authors:
 * <ul>
 *   <li>Do all non-deterministic work via {@link #executeActivity} or {@link #sideEffect}.</li>
 *   <li>Never call {@code Instant.now()}, {@code UUID.randomUUID()}, RNGs, or do I/O directly —
 *       use {@link #now()} / {@link #randomUuid()} / {@link #sideEffect}.</li>
 *   <li>Workflow code must be a pure function of its input and history.</li>
 * </ul>
 */
public class WorkflowContext {

    private final String workflowId;
    private final String inputJson;
    private final Json json;

    // Reconstructed from history (all keyed by command sequence):
    private final Map<Long, String> completedResults;
    private final Map<Long, String> failedActivities;
    private final Map<Long, String> recordedSideEffects;
    private final Set<Long> scheduledPending;

    // Produced during this run:
    private final List<Commands.RecordSideEffect> newSideEffects = new ArrayList<>();
    private Commands.ScheduleActivity pendingSchedule;

    private long commandCounter = 0;

    public WorkflowContext(String workflowId, String inputJson, Json json,
                           Map<Long, String> completedResults,
                           Map<Long, String> failedActivities,
                           Map<Long, String> recordedSideEffects,
                           Set<Long> scheduledPending) {
        this.workflowId = workflowId;
        this.inputJson = inputJson;
        this.json = json;
        this.completedResults = completedResults;
        this.failedActivities = failedActivities;
        this.recordedSideEffects = recordedSideEffects;
        this.scheduledPending = scheduledPending;
    }

    public String workflowId() {
        return workflowId;
    }

    public String rawInput() {
        return inputJson;
    }

    public <T> T input(Class<T> type) {
        return json.read(inputJson, type);
    }

    /**
     * Schedule an activity and return its result.
     *
     * If the result is already in history, returns it (replay — the activity is
     * NOT executed again). If the activity permanently failed, throws
     * {@link ActivityFailedException} so the workflow can branch. Otherwise it
     * records the intent to schedule and suspends the workflow.
     */
    public <T> T executeActivity(String activityType, Object input, ActivityOptions options, Class<T> resultType) {
        long seq = ++commandCounter;

        if (completedResults.containsKey(seq)) {
            return json.read(completedResults.get(seq), resultType);
        }
        if (failedActivities.containsKey(seq)) {
            throw new ActivityFailedException(activityType, failedActivities.get(seq));
        }
        if (scheduledPending.contains(seq)) {
            // Already scheduled and still in flight — park.
            throw WorkflowBlockedException.INSTANCE;
        }
        // Brand new work. Record the intent and suspend (sequential execution model).
        this.pendingSchedule = new Commands.ScheduleActivity(
                seq, activityType, json.write(input),
                options.getMaxAttempts(), options.getTimeoutSeconds());
        throw WorkflowBlockedException.INSTANCE;
    }

    public <T> T executeActivity(String activityType, Object input, Class<T> resultType) {
        return executeActivity(activityType, input, ActivityOptions.defaults(), resultType);
    }

    /**
     * Capture a non-deterministic value computed inside the workflow so that it
     * is identical on every replay. The supplier runs at most once, ever.
     */
    public <T> T sideEffect(Supplier<T> supplier, Class<T> type) {
        long seq = ++commandCounter;
        if (recordedSideEffects.containsKey(seq)) {
            return json.read(recordedSideEffects.get(seq), type);
        }
        T value = supplier.get();
        String serialized = json.write(value);
        recordedSideEffects.put(seq, serialized);
        newSideEffects.add(new Commands.RecordSideEffect(seq, serialized));
        return value;
    }

    /** Deterministic clock — the wall-clock time is captured once and replayed. */
    public Instant now() {
        Long millis = sideEffect(() -> System.currentTimeMillis(), Long.class);
        return Instant.ofEpochMilli(millis);
    }

    /** Deterministic UUID — generated once and replayed thereafter. */
    public String randomUuid() {
        return sideEffect(() -> UUID.randomUUID().toString(), String.class);
    }

    // ---- accessed by the executor after a run ----

    List<Commands.RecordSideEffect> newSideEffects() {
        return newSideEffects;
    }

    Commands.ScheduleActivity pendingSchedule() {
        return pendingSchedule;
    }
}
