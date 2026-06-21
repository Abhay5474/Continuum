package io.continuum.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.continuum.common.Json;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the determinism guarantees of the replay engine in isolation — no
 * database, no Spring. This is the contract everything else relies on.
 */
class WorkflowExecutorTest {

    private final Json json = new Json(new ObjectMapper().registerModule(new JavaTimeModule()));
    private final WorkflowExecutor executor = new WorkflowExecutor(json);

    /** A workflow with one side effect and two sequential activities. */
    static class SampleWorkflow implements Workflow {
        final AtomicInteger sideEffectCalls;

        SampleWorkflow(AtomicInteger sideEffectCalls) {
            this.sideEffectCalls = sideEffectCalls;
        }

        @Override
        public String type() {
            return "Sample";
        }

        @Override
        public Object execute(WorkflowContext ctx) {
            String token = ctx.sideEffect(() -> {
                sideEffectCalls.incrementAndGet();
                return "captured-value";
            }, String.class);
            String a = ctx.executeActivity("A", Map.of("x", 1), String.class);
            String b = ctx.executeActivity("B", Map.of("prev", a), String.class);
            return Map.of("token", token, "a", a, "b", b);
        }
    }

    @Test
    void runsDeterministicallyAndCheckpointsStepByStep() {
        AtomicInteger sideEffectCalls = new AtomicInteger();
        SampleWorkflow wf = new SampleWorkflow(sideEffectCalls);

        Map<Long, String> completed = new HashMap<>();
        Map<Long, String> failed = new HashMap<>();
        Map<Long, String> sideEffects = new HashMap<>();
        Set<Long> scheduled = new HashSet<>();

        // --- Decision 1: capture side effect, then schedule activity A ---
        Commands.Decision d1 = run(wf, completed, failed, sideEffects, scheduled);
        assertEquals(Commands.Decision.Kind.SCHEDULE, d1.kind());
        assertEquals(1, d1.sideEffects().size(), "side effect captured on first run");
        assertEquals(2, d1.schedule().commandSeq());
        assertEquals("A", d1.schedule().activityType());
        assertEquals(1, sideEffectCalls.get());
        // engine persists outcomes:
        sideEffects.put(d1.sideEffects().get(0).commandSeq(), d1.sideEffects().get(0).value());
        scheduled.add(d1.schedule().commandSeq());

        // --- Decision while A still pending: must BLOCK and NOT re-run side effect ---
        Commands.Decision blocked = run(wf, completed, failed, sideEffects, scheduled);
        assertEquals(Commands.Decision.Kind.BLOCKED, blocked.kind());
        assertEquals(1, sideEffectCalls.get(), "side effect supplier must not run again on replay");

        // --- A completes -> Decision 2 schedules B ---
        completed.put(2L, json.write("result-A"));
        scheduled.remove(2L);
        Commands.Decision d2 = run(wf, completed, failed, sideEffects, scheduled);
        assertEquals(Commands.Decision.Kind.SCHEDULE, d2.kind());
        assertEquals(3, d2.schedule().commandSeq());
        assertEquals("B", d2.schedule().activityType());
        scheduled.add(3L);

        // --- B completes -> Decision 3 completes the workflow ---
        completed.put(3L, json.write("result-B"));
        scheduled.remove(3L);
        Commands.Decision d3 = run(wf, completed, failed, sideEffects, scheduled);
        assertEquals(Commands.Decision.Kind.COMPLETE, d3.kind());
        assertTrue(d3.result().contains("captured-value"));
        assertTrue(d3.result().contains("result-A"));
        assertTrue(d3.result().contains("result-B"));
        assertEquals(1, sideEffectCalls.get(), "side effect ran exactly once across the whole execution");

        // --- Determinism: replaying the final history yields an identical result ---
        Commands.Decision again = run(wf, completed, failed, sideEffects, scheduled);
        assertEquals(Commands.Decision.Kind.COMPLETE, again.kind());
        assertEquals(d3.result(), again.result(), "same history -> identical decision");
    }

    @Test
    void permanentActivityFailurePropagatesToWorkflowFailure() {
        SampleWorkflow wf = new SampleWorkflow(new AtomicInteger());
        Map<Long, String> completed = new HashMap<>();
        Map<Long, String> failed = new HashMap<>();
        Map<Long, String> sideEffects = new HashMap<>();
        Set<Long> scheduled = new HashSet<>();

        // Side effect already recorded; activity A (seq 2) terminally failed.
        sideEffects.put(1L, json.write("captured-value"));
        failed.put(2L, "provider exploded");

        Commands.Decision decision = run(wf, completed, failed, sideEffects, scheduled);
        assertEquals(Commands.Decision.Kind.FAIL, decision.kind());
        assertTrue(decision.error().contains("A"));
    }

    private Commands.Decision run(Workflow wf, Map<Long, String> completed, Map<Long, String> failed,
                                  Map<Long, String> sideEffects, Set<Long> scheduled) {
        return executor.runDecision(wf, "wf-1", "{}",
                new HashMap<>(completed), new HashMap<>(failed),
                new HashMap<>(sideEffects), new HashSet<>(scheduled));
    }
}
