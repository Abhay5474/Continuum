package io.continuum.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.continuum.common.Json;
import io.continuum.core.activity.ActivityContext;
import io.continuum.core.workflow.Commands;
import io.continuum.core.workflow.WorkflowExecutor;
import io.continuum.dag.DagModels.Claim;
import io.continuum.dag.DagModels.DagResult;
import io.continuum.dag.DagModels.Plan;
import io.continuum.dag.DagModels.SolverOutput;
import io.continuum.dag.activities.DagPlanActivity;
import io.continuum.dag.activities.DagSolverActivity;
import io.continuum.dag.activities.DagSynthesisActivity;
import io.continuum.dag.activities.DagVerifierActivity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end contract of the Consensus DAG on pure V1 replay mechanics: the
 * harness simulates the engine loop (decisions + a worker executing scheduled
 * activities), using the REAL deterministic verifier and synthesis activities
 * and scripted planner/solver outputs. Proves the full pipeline: plan →
 * parallel fan-out (multi-schedule decisions) → barrier → conflict graph →
 * Bayesian resolution → narration, all deterministic on replay.
 */
class ConsensusDagWorkflowTest {

    private final Json json = new Json(new ObjectMapper().registerModule(new JavaTimeModule()));
    private final WorkflowExecutor executor = new WorkflowExecutor(json);
    private final ConsensusDagWorkflow workflow = new ConsensusDagWorkflow();
    private final DagVerifierActivity verifier = new DagVerifierActivity(json);
    private final DagSynthesisActivity synthesis = new DagSynthesisActivity();

    private final Plan scriptedPlan = new Plan("Should the wire transfer be approved?", List.of(
            new Claim(1, "the wire transfer risk screening outcome", List.of(),
                    List.of("LOGIC_CONSISTENCY", "EVIDENCE_GROUNDING")),
            new Claim(2, "the wire transfer final approval decision", List.of(1),
                    List.of("LOGIC_CONSISTENCY", "EVIDENCE_GROUNDING"))));

    /** Scripted worker: planner/solver responses are canned; verifiers/synthesis are REAL. */
    private Object executeActivity(Commands.ScheduleActivity s) throws Exception {
        ActivityContext ctx = new ActivityContext("wf-1", "wf-1:" + s.commandSeq(), 1, json);
        return switch (s.activityType()) {
            case DagPlanActivity.TYPE -> scriptedPlan;
            case DagSolverActivity.TYPE -> {
                var node = json.mapper().readTree(s.input());
                int claimId = node.get("claimId").asInt();
                yield claimId == 1
                        ? new SolverOutput(1,
                        "The wire transfer risk screening examined the transfer history and found "
                                + "no suspicious patterns; the screening passed. Confidence: 0.85",
                        "The wire transfer risk screening passed with no suspicious patterns.",
                        0.85, "mock", "m", 40, 0.0001)
                        : new SolverOutput(2,
                        "Given the passed screening and sufficient balance, the wire transfer "
                                + "approval decision follows. The transfer is approved. Confidence: 0.8",
                        "The wire transfer is approved.",
                        0.8, "mock", "m", 40, 0.0001);
            }
            case DagVerifierActivity.TYPE -> verifier.execute(s.input(), ctx);
            case DagSynthesisActivity.TYPE -> synthesis.execute(s.input(), ctx);
            default -> throw new IllegalStateException("unexpected activity " + s.activityType());
        };
    }

    @Test
    void fullPipelineCompletesWithVerifiedAnswerAndFanOutDecisions() throws Exception {
        Map<Long, String> completed = new HashMap<>();
        Map<Long, String> failed = new HashMap<>();
        Map<Long, String> sideEffects = new HashMap<>();
        Set<Long> scheduled = new HashSet<>();
        String input = json.write(new ConsensusDagWorkflow.Input("dev-1",
                "Should the wire transfer be approved?"));

        int solverFanOut = 0;
        int verifierFanOut = 0;
        Commands.Decision decision = null;
        for (int tick = 0; tick < 20; tick++) {
            decision = executor.runDecision(workflow, "wf-1", input,
                    new HashMap<>(completed), new HashMap<>(failed),
                    new HashMap<>(sideEffects), new HashSet<>(scheduled));
            if (decision.kind() != Commands.Decision.Kind.SCHEDULE) {
                break;
            }
            if (decision.schedules().size() > 1) {
                String type = decision.schedules().get(0).activityType();
                if (DagSolverActivity.TYPE.equals(type)) {
                    solverFanOut = decision.schedules().size();
                } else if (DagVerifierActivity.TYPE.equals(type)) {
                    verifierFanOut = decision.schedules().size();
                }
            }
            // "Worker pool": execute every scheduled activity and record completion.
            for (Commands.ScheduleActivity s : decision.schedules()) {
                completed.put(s.commandSeq(), json.write(executeActivity(s)));
            }
        }

        assertEquals(Commands.Decision.Kind.COMPLETE, decision.kind(),
                "the DAG must complete: " + decision.kind() + " / " + decision.error());
        assertEquals(2, solverFanOut, "one parallel solver per claim in a single decision");
        assertEquals(4, verifierFanOut, "one parallel verifier per (claim, check) in a single decision");

        DagResult result = json.read(decision.result(), DagResult.class);
        assertTrue(result.finalConfidence() > 0.5,
                "consistent, grounded claims must survive: " + result.finalConfidence());
        assertEquals(2, result.aggregation().spine().size(), "both claims survive verification");
        assertTrue(result.answer().contains("The wire transfer is approved"),
                "the verified answer carries the surviving conclusions");
        assertTrue(result.answer().contains("Verification summary"),
                "the narration explains the mathematical resolution");
        assertTrue(result.riskFlags().stream().anyMatch(f -> f.contains("Hallucination risk")));

        // Determinism: replaying the identical history yields the identical result.
        Commands.Decision replay = executor.runDecision(workflow, "wf-1", input,
                new HashMap<>(completed), new HashMap<>(failed),
                new HashMap<>(sideEffects), new HashSet<>(scheduled));
        assertEquals(decision.result(), replay.result(), "the whole DAG is replay-deterministic");
    }
}
