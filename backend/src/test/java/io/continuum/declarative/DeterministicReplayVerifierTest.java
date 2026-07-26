package io.continuum.declarative;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.common.Json;
import io.continuum.core.event.EventType;
import io.continuum.core.event.Payloads;
import io.continuum.persistence.entity.WorkflowEventEntity;
import io.continuum.persistence.repository.WorkflowEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Replay verification for a workflow built from HTTP steps.
 *
 * <p>The property under test is the one durability actually rests on: replaying
 * the recorded history must reproduce the same decisions — the same steps, in
 * the same order, resolving to the same calls. Nothing here performs I/O, which
 * is the point: verifying a payment workflow must not issue a second payment.
 */
class DeterministicReplayVerifierTest {

    private static final String WF = "wf-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Json json = new Json(mapper);
    private WorkflowEventRepository events;
    private DeterministicReplayVerifier verifier;
    private long seq;
    private List<WorkflowEventEntity> history;

    @BeforeEach
    void setUp() {
        events = mock(WorkflowEventRepository.class);
        verifier = new DeterministicReplayVerifier(events, mapper, json);
        history = new ArrayList<>();
        seq = 0;
        when(events.findByWorkflowIdOrderBySequenceNumberAsc(anyString())).thenReturn(history);
    }

    // --- history construction -------------------------------------------------

    private void recordCall(long commandSeq, String stepId, String url, Object body, Object responseBody) {
        String input = json.write(new HttpStepActivity.Input(url, "POST", Map.of(), body, 30, WF + ":" + stepId));
        history.add(new WorkflowEventEntity(WF, seq++, EventType.ACTIVITY_SCHEDULED,
                json.write(new Payloads.ActivityScheduled(commandSeq, HttpStepActivity.TYPE, input, 4, 30,
                        WF + ":" + commandSeq))));
        history.add(new WorkflowEventEntity(WF, seq++, EventType.ACTIVITY_COMPLETED,
                json.write(new Payloads.ActivityCompleted(commandSeq, HttpStepActivity.TYPE,
                        json.write(Map.of("status", 200, "body", responseBody))))));
    }

    private String specJson(String confirmCondition) {
        return """
                {
                  "steps": [
                    { "id": "reserve",
                      "call": { "method": "POST", "url": "https://api.acme.com/reserve",
                                "body": { "sku": "${input.sku}" } } },
                    { "id": "confirm", "dependsOn": ["reserve"],
                      %s
                      "call": { "method": "POST", "url": "https://api.acme.com/confirm",
                                "body": { "hold": "${steps.reserve.holdId}" } } }
                  ]
                }
                """.formatted(confirmCondition == null ? "" : "\"condition\": \"" + confirmCondition + "\",");
    }

    private String runInput(String spec) {
        Map<String, Object> run = Map.of(
                "definition", "order-flow", "version", 1,
                "spec", readMap(spec),
                "input", Map.of("sku", "WIDGET-1"));
        return json.write(run);
    }

    private Map<String, Object> readMap(String s) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = mapper.readValue(s, Map.class);
            return m;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // --- tests ---------------------------------------------------------------

    @Test
    @DisplayName("an unchanged run replays to the same calls")
    void faithfulReplayPasses() {
        String spec = specJson(null);
        recordCall(1, "reserve", "https://api.acme.com/reserve", Map.of("sku", "WIDGET-1"),
                Map.of("holdId", "HOLD-77"));
        recordCall(2, "confirm", "https://api.acme.com/confirm", Map.of("hold", "HOLD-77"),
                Map.of("ok", true));

        var result = verifier.verify(WF, DeclarativeWorkflow.TYPE, runInput(spec));

        assertThat(result.applicable()).isTrue();
        assertThat(result.checked()).isEqualTo(2);
        assertThat(result.diverged()).isZero();
        assertThat(result.checks()).extracting(DeterministicReplayVerifier.StepCheck::stepId)
                .containsExactly("reserve", "confirm");
    }

    @Test
    @DisplayName("a step whose reference now resolves differently is reported as diverged")
    void divergentTemplateIsCaught() {
        String spec = specJson(null);
        recordCall(1, "reserve", "https://api.acme.com/reserve", Map.of("sku", "WIDGET-1"),
                Map.of("holdId", "HOLD-77"));
        // The history says confirm was called with a different hold than the spec
        // would now derive from reserve's recorded result.
        recordCall(2, "confirm", "https://api.acme.com/confirm", Map.of("hold", "HOLD-OTHER"),
                Map.of("ok", true));

        var result = verifier.verify(WF, DeclarativeWorkflow.TYPE, runInput(spec));

        assertThat(result.diverged()).isEqualTo(1);
        var confirm = result.checks().stream().filter(c -> c.stepId().equals("confirm")).findFirst().orElseThrow();
        assertThat(confirm.matched()).isFalse();
        assertThat(confirm.expected()).contains("HOLD-77");
        assertThat(confirm.recorded()).contains("HOLD-OTHER");
    }

    @Test
    @DisplayName("a guard that was false is confirmed to have scheduled nothing")
    void skippedStepIsVerified() {
        String spec = specJson("${steps.reserve.inStock} == true");
        recordCall(1, "reserve", "https://api.acme.com/reserve", Map.of("sku", "WIDGET-1"),
                Map.of("holdId", "HOLD-77", "inStock", false));

        var result = verifier.verify(WF, DeclarativeWorkflow.TYPE, runInput(spec));

        assertThat(result.diverged()).isZero();
        var guard = result.checks().stream().filter(c -> c.kind().equals("guard")).findFirst().orElseThrow();
        assertThat(guard.stepId()).isEqualTo("confirm");
        assertThat(guard.matched()).isTrue();
        assertThat(guard.detail()).contains("correctly skipped");
    }

    @Test
    @DisplayName("a guard that is false on replay but ran originally is a divergence")
    void nonDeterministicGuardIsCaught() {
        String spec = specJson("${steps.reserve.inStock} == true");
        recordCall(1, "reserve", "https://api.acme.com/reserve", Map.of("sku", "WIDGET-1"),
                Map.of("holdId", "HOLD-77", "inStock", false));
        // But the history shows confirm executed anyway.
        recordCall(2, "confirm", "https://api.acme.com/confirm", Map.of("hold", "HOLD-77"),
                Map.of("ok", true));

        var result = verifier.verify(WF, DeclarativeWorkflow.TYPE, runInput(spec));

        assertThat(result.diverged()).isEqualTo(1);
        var guard = result.checks().stream().filter(c -> c.kind().equals("guard")).findFirst().orElseThrow();
        assertThat(guard.matched()).isFalse();
        assertThat(guard.detail()).contains("executed originally");
    }

    @Test
    @DisplayName("a non-declarative workflow is reported as not applicable, never as a pass")
    void otherWorkflowTypesAreNotApplicable() {
        var result = verifier.verify(WF, "CustomerAnalysis", "{}");

        assertThat(result.applicable()).isFalse();
        assertThat(result.checked()).isZero();
    }
}
