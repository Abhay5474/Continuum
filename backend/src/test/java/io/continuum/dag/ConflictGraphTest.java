package io.continuum.dag;

import io.continuum.dag.DagModels.Claim;
import io.continuum.dag.DagModels.GraphEdge;
import io.continuum.dag.DagModels.Plan;
import io.continuum.dag.DagModels.SolverOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Step 3: contradiction detection reuses V2 decision-polarity semantics. */
class ConflictGraphTest {

    private SolverOutput solver(int id, String conclusion) {
        return new SolverOutput(id, conclusion, conclusion, 0.7, "mock", "m", 10, 0);
    }

    @Test
    void opposingConclusionsOnTheSameTopicContradict() {
        Plan plan = new Plan("loan decision", List.of(
                new Claim(1, "the loan application risk assessment", List.of(), List.of()),
                new Claim(2, "the loan application final decision", List.of(), List.of())));
        List<GraphEdge> edges = ConflictGraph.build(plan, List.of(
                solver(1, "The loan application should be approved because income is sufficient."),
                solver(2, "The loan application must be rejected due to insufficient income.")));

        assertTrue(edges.stream().anyMatch(e -> GraphEdge.CONTRADICTS.equals(e.type())),
                "approve vs reject on the same loan must contradict: " + edges);
    }

    @Test
    void consistentConclusionsSupportAndUnrelatedClaimsStayDisconnected() {
        Plan plan = new Plan("mixed", List.of(
                new Claim(1, "the deployment release safety check", List.of(), List.of()),
                new Claim(2, "the deployment release approval decision", List.of(), List.of()),
                new Claim(3, "the office lunch menu preference", List.of(), List.of())));
        List<GraphEdge> edges = ConflictGraph.build(plan, List.of(
                solver(1, "The deployment release is safe and the checks passed."),
                solver(2, "The deployment release is approved, all checks passed successfully."),
                solver(3, "Pizza is on the lunch menu this week.")));

        assertTrue(edges.stream().anyMatch(e -> GraphEdge.SUPPORTS.equals(e.type())
                        && e.fromClaim() == 1 && e.toClaim() == 2),
                "agreeing conclusions on the same topic support each other: " + edges);
        assertTrue(edges.stream().noneMatch(e -> e.fromClaim() == 3 || e.toClaim() == 3),
                "unrelated claims form no semantic edges");
    }

    @Test
    void plannerDependenciesBecomeDependsEdges() {
        Plan plan = new Plan("t", List.of(
                new Claim(1, "first premise", List.of(), List.of()),
                new Claim(2, "conclusion built on the first premise", List.of(1), List.of())));
        List<GraphEdge> edges = ConflictGraph.build(plan,
                List.of(solver(1, "ok."), solver(2, "ok.")));
        assertTrue(edges.stream().anyMatch(e -> GraphEdge.DEPENDS.equals(e.type())
                && e.fromClaim() == 1 && e.toClaim() == 2));
    }
}
