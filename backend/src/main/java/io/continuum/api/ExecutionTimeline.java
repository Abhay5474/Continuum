package io.continuum.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.continuum.common.Json;
import io.continuum.core.event.EventType;
import io.continuum.persistence.entity.WorkflowEventEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A run's steps as they executed: when each was scheduled, every attempt it
 * took, how each attempt ended and how long a retry waited. Read from the event
 * log alone, so it exists while the run is still going — a projection such as
 * the verification trace is only written once the run has finished.
 *
 * <p>Payload bodies (inputs, results) are left out apart from the few fields
 * that name a step: a console polling a running workflow should not download
 * every model answer again every few seconds.
 */
public final class ExecutionTimeline {

    private static final int MAX_ERROR = 240;

    private ExecutionTimeline() {
    }

    /** One try at a step. {@code outcome} is RUNNING, COMPLETED or FAILED. */
    public record Attempt(int attempt, Instant startedAt, Instant endedAt, String outcome, String error,
                          String workerId, Instant retryAt) {
    }

    /**
     * One activity the workflow scheduled. {@code status} is SCHEDULED, RUNNING,
     * RETRYING (an attempt failed and another is due), COMPLETED or FAILED.
     */
    public record Step(long seq, String activityType, Integer claimId, String check, String name,
                       Instant scheduledAt, String status, int maxAttempts, List<Attempt> attempts) {
    }

    /** {@code status} is RUNNING, COMPLETED, FAILED or CANCELLED. */
    public record Run(String status, Instant startedAt, Instant endedAt, String error, List<Step> steps) {
    }

    /** An event as the timeline needs it. */
    public record Event(EventType type, String payload, Instant at) {
    }

    public static Run fromEntities(List<WorkflowEventEntity> events, Json json) {
        return of(events.stream().map(e -> new Event(e.getEventType(), e.getPayload(), e.getCreatedAt())).toList(),
                json);
    }

    public static Run of(List<Event> events, Json json) {
        Map<Long, StepBuilder> steps = new LinkedHashMap<>();
        String status = "RUNNING";
        Instant started = null;
        Instant ended = null;
        String error = null;
        for (Event e : events) {
            JsonNode p = parse(e.payload(), json);
            switch (e.type()) {
                case WORKFLOW_STARTED -> started = e.at();
                case WORKFLOW_COMPLETED -> {
                    status = "COMPLETED";
                    ended = e.at();
                }
                case WORKFLOW_FAILED -> {
                    error = clip(p.path("error").asText(null));
                    status = error != null && error.startsWith(WorkflowQueryService.CANCELLED_PREFIX)
                            ? "CANCELLED" : "FAILED";
                    ended = e.at();
                }
                case ACTIVITY_SCHEDULED -> {
                    long seq = p.path("commandSeq").asLong();
                    StepBuilder s = new StepBuilder(seq, p.path("activityType").asText(null), e.at());
                    s.maxAttempts = p.path("maxAttempts").asInt(1);
                    JsonNode input = parse(p.path("input").asText(null), json);
                    if (input.hasNonNull("claimId") && input.path("claimId").canConvertToInt()) {
                        s.claimId = input.path("claimId").asInt();
                    }
                    s.check = input.hasNonNull("check") ? input.path("check").asText() : null;
                    s.name = nameOf(input);
                    steps.put(seq, s);
                }
                case ACTIVITY_STARTED -> step(steps, p).start(p.path("attempt").asInt(1), e.at(),
                        p.path("workerId").asText(null));
                case ACTIVITY_COMPLETED -> step(steps, p).end(0, e.at(), "COMPLETED", null, true);
                case ACTIVITY_FAILED -> step(steps, p).end(p.path("attempt").asInt(0), e.at(), "FAILED",
                        clip(p.path("error").asText(null)), p.path("terminal").asBoolean(true));
                case RETRY_SCHEDULED -> {
                    StepBuilder s = step(steps, p);
                    s.status = "RETRYING";
                    Attempt last = s.attempts.isEmpty() ? null : s.attempts.get(s.attempts.size() - 1);
                    Instant at = instant(p.path("visibleAt").asText(null));
                    if (last != null && at != null) {
                        s.attempts.set(s.attempts.size() - 1, new Attempt(last.attempt(), last.startedAt(),
                                last.endedAt(), last.outcome(), last.error(), last.workerId(), at));
                    }
                }
                default -> {
                    // Side effects and timers do not change a step.
                }
            }
        }
        return new Run(status, started, ended, error, steps.values().stream().map(StepBuilder::build).toList());
    }

    /**
     * The step an event belongs to. A step whose scheduling event is missing
     * (a history trimmed by retention, or a recovery that failed it) is still
     * shown rather than dropped.
     */
    private static StepBuilder step(Map<Long, StepBuilder> steps, JsonNode p) {
        long seq = p.path("commandSeq").asLong();
        return steps.computeIfAbsent(seq, k -> new StepBuilder(k, p.path("activityType").asText(null), null));
    }

    /**
     * The name the author gave a declarative step. It signs its call with
     * {@code <workflowId>:<stepId>}; a compensation with {@code :compensate:}.
     */
    private static String nameOf(JsonNode input) {
        String key = input.hasNonNull("idempotencyKey") ? input.path("idempotencyKey").asText()
                : input.hasNonNull("stepId") ? input.path("stepId").asText() : null;
        if (key == null || key.isBlank()) {
            return null;
        }
        String step = key.contains(":") ? key.substring(key.lastIndexOf(':') + 1) : key;
        return key.contains(":compensate:") ? "undo " + step : step;
    }

    private static JsonNode parse(String text, Json json) {
        if (text == null || text.isBlank()) {
            return json.mapper().missingNode();
        }
        try {
            JsonNode n = json.mapper().readTree(text);
            return n == null ? json.mapper().missingNode() : n;
        } catch (Exception e) {
            return json.mapper().missingNode();
        }
    }

    private static Instant instant(String text) {
        try {
            return text == null ? null : Instant.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static String clip(String s) {
        return s == null || s.length() <= MAX_ERROR ? s : s.substring(0, MAX_ERROR - 1) + "…";
    }

    private static final class StepBuilder {
        final long seq;
        final String activityType;
        final Instant scheduledAt;
        Integer claimId;
        String check;
        String name;
        int maxAttempts = 1;
        String status = "SCHEDULED";
        final List<Attempt> attempts = new ArrayList<>();

        StepBuilder(long seq, String activityType, Instant scheduledAt) {
            this.seq = seq;
            this.activityType = activityType;
            this.scheduledAt = scheduledAt;
        }

        StepBuilder start(int attempt, Instant at, String worker) {
            attempts.add(new Attempt(attempt, at, null, "RUNNING", null, worker, null));
            status = "RUNNING";
            return this;
        }

        /**
         * Closes the running attempt. One that ends with no recorded start (a
         * recovery sweep failing a lost task) is shown as an instant.
         */
        void end(int attempt, Instant at, String outcome, String error, boolean terminal) {
            int i = attempts.size() - 1;
            if (i < 0 || !"RUNNING".equals(attempts.get(i).outcome())) {
                attempts.add(new Attempt(attempt > 0 ? attempt : attempts.size() + 1, at, at, outcome, error,
                        null, null));
            } else {
                Attempt a = attempts.get(i);
                attempts.set(i, new Attempt(a.attempt(), a.startedAt(), at, outcome, error, a.workerId(), null));
            }
            status = "COMPLETED".equals(outcome) ? "COMPLETED" : terminal ? "FAILED" : "RETRYING";
        }

        Step build() {
            return new Step(seq, activityType, claimId, check, name, scheduledAt, status, maxAttempts,
                    List.copyOf(attempts));
        }
    }
}
