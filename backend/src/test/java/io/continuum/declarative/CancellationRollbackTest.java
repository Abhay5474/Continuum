package io.continuum.declarative;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.core.event.EventStore;
import io.continuum.core.event.EventType;
import io.continuum.persistence.entity.WorkflowEventEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a cancelled run had done, read back from its history: that is exactly
 * what its rollback may undo, so getting it wrong either leaves an effect in
 * place or "undoes" something that never happened.
 */
class CancellationRollbackTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<WorkflowEventEntity> history = new ArrayList<>();
    private long seq;

    private void event(EventType type, Map<String, Object> payload) throws Exception {
        history.add(new WorkflowEventEntity("wf", seq++, type, mapper.writeValueAsString(payload)));
    }

    private void scheduleHttp(long cmd, String key) throws Exception {
        event(EventType.ACTIVITY_SCHEDULED, Map.of("commandSeq", cmd, "activityType", HttpStepActivity.TYPE,
                "input", mapper.writeValueAsString(Map.of("idempotencyKey", key))));
    }

    @Test
    void completedInOrderStartedButUnfinishedIsInFlightNeverStartedIsNeither() throws Exception {
        scheduleHttp(1, "wf:reserve");
        event(EventType.ACTIVITY_STARTED, Map.of("commandSeq", 1, "activityType", HttpStepActivity.TYPE));
        event(EventType.ACTIVITY_COMPLETED, Map.of("commandSeq", 1, "activityType", HttpStepActivity.TYPE,
                "result", mapper.writeValueAsString(Map.of("status", 200, "body", Map.of("holdId", "h1")))));
        scheduleHttp(2, "wf:charge");   // started, never finished: in flight
        event(EventType.ACTIVITY_STARTED, Map.of("commandSeq", 2, "activityType", HttpStepActivity.TYPE));
        scheduleHttp(3, "wf:email");    // withdrawn by the cancel: never ran
        event(EventType.ACTIVITY_SCHEDULED, Map.of("commandSeq", 4, "activityType", WaitStepActivity.TYPE,
                "input", mapper.writeValueAsString(Map.of("stepId", "pause", "seconds", 60))));
        event(EventType.ACTIVITY_STARTED, Map.of("commandSeq", 4, "activityType", WaitStepActivity.TYPE));

        EventStore store = mock(EventStore.class);
        when(store.history("wf")).thenReturn(history);
        var p = new CancellationRollback(null, store, null, mapper).progress("wf");

        assertThat(p.completed()).containsExactly("reserve");
        assertThat(p.results().get("reserve")).isEqualTo(Map.of("holdId", "h1"));
        assertThat(p.inFlight()).containsExactly("charge");
    }

    @Test
    void compensationsAndCallbacksAreNotForwardSteps() throws Exception {
        scheduleHttp(1, "wf:compensate:reserve");
        event(EventType.ACTIVITY_STARTED, Map.of("commandSeq", 1, "activityType", HttpStepActivity.TYPE));
        scheduleHttp(2, "wf:onComplete");
        event(EventType.ACTIVITY_STARTED, Map.of("commandSeq", 2, "activityType", HttpStepActivity.TYPE));

        EventStore store = mock(EventStore.class);
        when(store.history("wf")).thenReturn(history);
        var p = new CancellationRollback(null, store, null, mapper).progress("wf");

        assertThat(p.completed()).isEmpty();
        assertThat(p.inFlight()).isEmpty();
    }
}
