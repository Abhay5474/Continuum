package io.continuum.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.common.Json;
import io.continuum.core.event.EventType;
import io.continuum.core.event.Payloads;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionTimelineTest {

    private final Json json = new Json(new ObjectMapper());
    private final Instant t0 = Instant.parse("2026-10-29T10:00:00Z");
    private final List<ExecutionTimeline.Event> log = new ArrayList<>();

    private void at(long ms, EventType type, Object payload) {
        log.add(new ExecutionTimeline.Event(type, json.write(payload), t0.plusMillis(ms)));
    }

    @Test
    void aStepThatFailedOnceShowsBothAttemptsAndTheBackoffBetween() {
        at(0, EventType.WORKFLOW_STARTED, new Payloads.WorkflowStarted("ConsensusDag", "{}"));
        at(10, EventType.ACTIVITY_SCHEDULED, new Payloads.ActivityScheduled(1, "dag.solve",
                json.write(Map.of("claimId", 2, "statement", "s")), 3, 30, "k"));
        at(40, EventType.ACTIVITY_STARTED, new Payloads.ActivityStarted(1, "dag.solve", 1, "worker-a"));
        at(900, EventType.ACTIVITY_FAILED, new Payloads.ActivityFailed(1, "dag.solve", "provider 500", 1, false));
        at(901, EventType.RETRY_SCHEDULED, new Payloads.RetryScheduled(1, "dag.solve", 2,
                t0.plusMillis(1901).toString()));
        at(1950, EventType.ACTIVITY_STARTED, new Payloads.ActivityStarted(1, "dag.solve", 2, "worker-b"));
        at(2600, EventType.ACTIVITY_COMPLETED, new Payloads.ActivityCompleted(1, "dag.solve", "{}"));
        at(2700, EventType.WORKFLOW_COMPLETED, new Payloads.WorkflowCompleted("{}"));

        ExecutionTimeline.Run run = ExecutionTimeline.of(log, json);

        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.startedAt()).isEqualTo(t0);
        assertThat(run.endedAt()).isEqualTo(t0.plusMillis(2700));
        ExecutionTimeline.Step step = run.steps().get(0);
        assertThat(step.claimId()).isEqualTo(2);
        assertThat(step.status()).isEqualTo("COMPLETED");
        assertThat(step.maxAttempts()).isEqualTo(3);
        assertThat(step.scheduledAt()).isEqualTo(t0.plusMillis(10));
        assertThat(step.attempts()).hasSize(2);
        ExecutionTimeline.Attempt first = step.attempts().get(0);
        assertThat(first.outcome()).isEqualTo("FAILED");
        assertThat(first.error()).isEqualTo("provider 500");
        assertThat(first.workerId()).isEqualTo("worker-a");
        assertThat(first.retryAt()).isEqualTo(t0.plusMillis(1901));
        ExecutionTimeline.Attempt second = step.attempts().get(1);
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(second.startedAt()).isEqualTo(t0.plusMillis(1950));
        assertThat(second.endedAt()).isEqualTo(t0.plusMillis(2600));
        assertThat(second.outcome()).isEqualTo("COMPLETED");
    }

    @Test
    void aRunStillGoingShowsWhatIsRunningAndWhatIsWaiting() {
        at(0, EventType.WORKFLOW_STARTED, new Payloads.WorkflowStarted("ConsensusDag", "{}"));
        at(5, EventType.ACTIVITY_SCHEDULED, new Payloads.ActivityScheduled(1, "dag.verify",
                json.write(Map.of("claimId", 1, "check", "LOGIC_CONSISTENCY")), 3, 30, "k1"));
        at(5, EventType.ACTIVITY_SCHEDULED, new Payloads.ActivityScheduled(2, "dag.verify",
                json.write(Map.of("claimId", 1, "check", "CONSTRAINT_CHECK")), 3, 30, "k2"));
        at(30, EventType.ACTIVITY_STARTED, new Payloads.ActivityStarted(1, "dag.verify", 1, "w"));

        ExecutionTimeline.Run run = ExecutionTimeline.of(log, json);

        assertThat(run.status()).isEqualTo("RUNNING");
        assertThat(run.endedAt()).isNull();
        assertThat(run.steps()).extracting(ExecutionTimeline.Step::status).containsExactly("RUNNING", "SCHEDULED");
        assertThat(run.steps()).extracting(ExecutionTimeline.Step::check)
                .containsExactly("LOGIC_CONSISTENCY", "CONSTRAINT_CHECK");
        assertThat(run.steps().get(0).attempts().get(0).endedAt()).isNull();
        assertThat(run.steps().get(1).attempts()).isEmpty();
    }

    @Test
    void aTerminalFailureEndsTheStepAndACancelledRunSaysSo() {
        at(0, EventType.WORKFLOW_STARTED, new Payloads.WorkflowStarted("Declarative", "{}"));
        at(1, EventType.ACTIVITY_SCHEDULED, new Payloads.ActivityScheduled(1, "httpStep",
                json.write(Map.of("idempotencyKey", "wf-1:charge-card")), 1, 30, "wf-1:charge-card"));
        at(2, EventType.ACTIVITY_STARTED, new Payloads.ActivityStarted(1, "httpStep", 1, "w"));
        at(3, EventType.ACTIVITY_FAILED, new Payloads.ActivityFailed(1, "httpStep", "x".repeat(400), 1, true));
        at(4, EventType.WORKFLOW_FAILED, new Payloads.WorkflowFailed("Cancelled: stopped from the console"));

        ExecutionTimeline.Run run = ExecutionTimeline.of(log, json);

        assertThat(run.status()).isEqualTo("CANCELLED");
        ExecutionTimeline.Step step = run.steps().get(0);
        assertThat(step.name()).isEqualTo("charge-card");
        assertThat(step.status()).isEqualTo("FAILED");
        assertThat(step.attempts().get(0).error()).hasSizeLessThanOrEqualTo(240).endsWith("…");
    }

    @Test
    void aFailureWithNoRecordedStartIsStillAnAttempt() {
        at(0, EventType.ACTIVITY_SCHEDULED, new Payloads.ActivityScheduled(7, "dag.plan", "not json", 3, 30, "k"));
        at(50, EventType.ACTIVITY_FAILED, new Payloads.ActivityFailed(7, "dag.plan", "timed out", 1, false));

        ExecutionTimeline.Step step = ExecutionTimeline.of(log, json).steps().get(0);

        assertThat(step.status()).isEqualTo("RETRYING");
        assertThat(step.claimId()).isNull();
        assertThat(step.attempts()).singleElement().satisfies(a -> {
            assertThat(a.attempt()).isEqualTo(1);
            assertThat(a.startedAt()).isEqualTo(a.endedAt());
            assertThat(a.outcome()).isEqualTo("FAILED");
        });
    }
}
