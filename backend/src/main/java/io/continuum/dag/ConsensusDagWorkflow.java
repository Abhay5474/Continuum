package io.continuum.dag;

import io.continuum.core.workflow.Workflow;
import io.continuum.core.workflow.WorkflowContext;
import io.continuum.dag.DagModels.Aggregation;
import io.continuum.dag.DagModels.Claim;
import io.continuum.dag.DagModels.DagResult;
import io.continuum.dag.DagModels.GraphEdge;
import io.continuum.dag.DagModels.Plan;
import io.continuum.dag.DagModels.SolverOutput;
import io.continuum.dag.DagModels.VerifierOutput;
import io.continuum.dag.activities.DagPlanActivity;
import io.continuum.dag.activities.DagSolverActivity;
import io.continuum.dag.activities.DagSynthesisActivity;
import io.continuum.dag.activities.DagVerifierActivity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * V6 — the Consensus DAG Workflow: a strictly deterministic V1 workflow that
 * compiles a task into a verification plan, fans out solver and verifier nodes
 * as parallel Postgres activities (one {@code ACTIVITY_SCHEDULED} event each,
 * executed by the existing worker pool — never in-process threads), builds a
 * contradiction graph, resolves truth by Bayesian evidence aggregation, and
 * narrates the outcome. Every step is a pure function of the event history,
 * so the entire DAG replays deterministically and survives crashes like any
 * other Continuum workflow.
 */
@Component
public class ConsensusDagWorkflow implements Workflow {

    public static final String TYPE = "ConsensusDag";

    public record Input(String developerId, String prompt) {
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object execute(WorkflowContext ctx) {
        Input in = ctx.input(Input.class);

        // Step 1 — compile the task into a typed verification plan.
        Plan plan = ctx.executeActivity(DagPlanActivity.TYPE,
                Map.of("prompt", in.prompt()), Plan.class);

        // Step 2a — fan out one solver node per claim (parallel, DB-backed).
        List<WorkflowContext.ParallelCall> solverCalls = new ArrayList<>();
        for (Claim c : plan.claims()) {
            solverCalls.add(new WorkflowContext.ParallelCall(DagSolverActivity.TYPE,
                    Map.of("claimId", c.id(), "statement", c.statement(), "task", plan.task())));
        }
        List<SolverOutput> solvers = ctx.executeActivitiesParallel(solverCalls, SolverOutput.class);

        // Step 2b — fan out verifier nodes per (claim, check) over solver outputs.
        List<WorkflowContext.ParallelCall> verifierCalls = new ArrayList<>();
        for (Claim c : plan.claims()) {
            SolverOutput solved = solvers.stream()
                    .filter(s -> s.claimId() == c.id()).findFirst().orElse(null);
            if (solved == null) {
                continue;
            }
            for (String check : c.checks()) {
                verifierCalls.add(new WorkflowContext.ParallelCall(DagVerifierActivity.TYPE,
                        Map.of("claimId", c.id(), "check", check, "statement", c.statement(),
                                "reasoning", solved.reasoning(), "conclusion", solved.conclusion())));
            }
        }
        List<VerifierOutput> verifiers =
                ctx.executeActivitiesParallel(verifierCalls, VerifierOutput.class);

        // Steps 3–4 — pure functions of recorded history: deterministic on replay.
        List<GraphEdge> edges = ConflictGraph.build(plan, solvers);
        Aggregation aggregation = BayesianAggregator.aggregate(plan, solvers, verifiers, edges);

        // Step 5 — post-hoc narration of the mathematical resolution.
        String answer = ctx.executeActivity(DagSynthesisActivity.TYPE,
                Map.of("plan", plan, "solvers", solvers, "aggregation", aggregation), String.class);

        int tokens = solvers.stream().mapToInt(SolverOutput::tokens).sum();
        double cost = solvers.stream().mapToDouble(SolverOutput::costUsd).sum();
        return new DagResult(answer, aggregation.finalConfidence(), aggregation.uncertainty(),
                plan, solvers, verifiers, edges, aggregation,
                riskFlags(aggregation, verifiers, edges), tokens, cost);
    }

    private static List<String> riskFlags(Aggregation agg, List<VerifierOutput> verifiers,
                                          List<GraphEdge> edges) {
        List<String> flags = new ArrayList<>();
        boolean anyContradiction = edges.stream()
                .anyMatch(e -> GraphEdge.CONTRADICTS.equals(e.type()));
        double worstLogic = verifiers.stream()
                .filter(v -> "LOGIC_CONSISTENCY".equals(v.check()))
                .mapToDouble(VerifierOutput::validity).min().orElse(1.0);
        flags.add(worstLogic < 0.4 ? "⚠ Hallucination risk: HIGH"
                : worstLogic < 0.7 ? "⚠ Hallucination risk: MEDIUM" : "✔ Hallucination risk: LOW");
        boolean schemaChecked = verifiers.stream().anyMatch(v -> "SCHEMA_ALIGNMENT".equals(v.check()));
        if (schemaChecked) {
            boolean schemaOk = verifiers.stream()
                    .filter(v -> "SCHEMA_ALIGNMENT".equals(v.check()))
                    .allMatch(v -> v.validity() >= 0.5);
            flags.add(schemaOk ? "✔ Schema validated" : "✘ Schema mismatch detected");
        }
        flags.add(anyContradiction
                ? (agg.spine().isEmpty() ? "✘ Contradiction unresolved" : "✔ Contradiction resolved")
                : "✔ No contradictions found");
        return flags;
    }
}
