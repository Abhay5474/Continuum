package io.continuum.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.continuum.common.Json;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The V6 fan-out contract on the V1 command layer: one decision schedules N
 * activities atomically, the workflow parks until ALL are recorded (barrier),
 * and the single-activity path is bit-for-bit unchanged.
 */
class ParallelFanOutTest {

    private final Json json = new Json(new ObjectMapper().registerModule(new JavaTimeModule()));
    private final WorkflowExecutor executor = new WorkflowExecutor(json);

    static class FanOutWorkflow implements Workflow {
        @Override
        public String type() {
            return "FanOut";
        }

        @Override
        public Object execute(WorkflowContext ctx) {
            String first = ctx.executeActivity("prep", Map.of(), String.class);
            List<String> parallel = ctx.executeActivitiesParallel(List.of(
                    new WorkflowContext.ParallelCall("solve", Map.of("n", 1)),
                    new WorkflowContext.ParallelCall("solve", Map.of("n", 2)),
                    new WorkflowContext.ParallelCall("verify", Map.of("n", 3))), String.class);
            return first + ":" + String.join(",", parallel);
        }
    }

    @Test
    void fanOutSchedulesAllBranchesInOneDecisionAndBarriersUntilComplete() {
        Map<Long, String> completed = new HashMap<>();
        Map<Long, String> failed = new HashMap<>();
        Map<Long, String> sideEffects = new HashMap<>();
        Set<Long> scheduled = new HashSet<>();
        Workflow wf = new FanOutWorkflow();

        // Decision 1: sequential prep schedules exactly one activity (V1 semantics).
        Commands.Decision d1 = run(wf, completed, failed, sideEffects, scheduled);
        assertEquals(Commands.Decision.Kind.SCHEDULE, d1.kind());
        assertEquals(1, d1.schedules().size());
        assertEquals("prep", d1.schedule().activityType());
        scheduled.add(d1.schedule().commandSeq());
        completed.put(d1.schedule().commandSeq(), json.write("P"));
        scheduled.remove(d1.schedule().commandSeq());

        // Decision 2: the fan-out — THREE ACTIVITY_SCHEDULED commands in ONE decision.
        Commands.Decision d2 = run(wf, completed, failed, sideEffects, scheduled);
        assertEquals(Commands.Decision.Kind.SCHEDULE, d2.kind());
        assertEquals(3, d2.schedules().size(), "fan-out lands as one atomic multi-schedule decision");
        assertEquals(List.of("solve", "solve", "verify"),
                d2.schedules().stream().map(Commands.ScheduleActivity::activityType).toList());
        d2.schedules().forEach(s -> scheduled.add(s.commandSeq()));

        // Barrier: with only 2 of 3 complete the workflow must stay BLOCKED
        // and must NOT re-schedule anything.
        long s1 = d2.schedules().get(0).commandSeq();
        long s2 = d2.schedules().get(1).commandSeq();
        long s3 = d2.schedules().get(2).commandSeq();
        completed.put(s1, json.write("A"));
        scheduled.remove(s1);
        completed.put(s2, json.write("B"));
        scheduled.remove(s2);
        Commands.Decision partial = run(wf, completed, failed, sideEffects, scheduled);
        assertEquals(Commands.Decision.Kind.BLOCKED, partial.kind(),
                "barrier holds until every branch is recorded");
        assertTrue(partial.schedules().isEmpty());

        // All three recorded → the workflow proceeds and completes.
        completed.put(s3, json.write("C"));
        scheduled.remove(s3);
        Commands.Decision done = run(wf, completed, failed, sideEffects, scheduled);
        assertEquals(Commands.Decision.Kind.COMPLETE, done.kind());
        assertTrue(done.result().contains("P:A,B,C"), "results return in call order: " + done.result());

        // Determinism: replaying the final history yields the identical result.
        Commands.Decision again = run(wf, completed, failed, sideEffects, scheduled);
        assertEquals(done.result(), again.result());
    }

    @Test
    void terminalFailureInsideAFanOutPropagatesAsActivityFailure() {
        Map<Long, String> completed = new HashMap<>();
        Map<Long, String> failed = new HashMap<>();
        Workflow wf = new FanOutWorkflow();
        completed.put(1L, "\"P\"");
        completed.put(2L, "\"A\"");
        failed.put(3L, "solver exploded");
        completed.put(4L, "\"C\"");

        Commands.Decision d = run(wf, completed, failed, new HashMap<>(), new HashSet<>());
        assertEquals(Commands.Decision.Kind.FAIL, d.kind());
        assertTrue(d.error().contains("solve"));
    }

    private Commands.Decision run(Workflow wf, Map<Long, String> completed, Map<Long, String> failed,
                                  Map<Long, String> sideEffects, Set<Long> scheduled) {
        return executor.runDecision(wf, "wf-1", "{}",
                new HashMap<>(completed), new HashMap<>(failed),
                new HashMap<>(sideEffects), new HashSet<>(scheduled));
    }
}
