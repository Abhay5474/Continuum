package io.continuum.dag;

import io.continuum.dag.DagModels.Claim;
import io.continuum.dag.DagModels.GraphEdge;
import io.continuum.dag.DagModels.Plan;
import io.continuum.dag.DagModels.SolverOutput;
import io.continuum.semantic.DecisionPolarity;
import io.continuum.semantic.TextVectors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 3 — the contradiction graph, built as a PURE function of recorded
 * history (plan + solver outputs), so it is deterministic on every replay.
 *
 * Reuses the V2 semantic machinery rather than reinventing scoring:
 * {@link TextVectors#cosine} for topical relatedness and
 * {@link DecisionPolarity#consistency} for decision-reversal detection.
 * Two claims on the same topic whose conclusions reverse each other CONTRADICT;
 * consistent conclusions on related topics SUPPORT; planner-declared
 * dependencies become DEPENDS edges.
 */
public final class ConflictGraph {

    private static final double RELATED_TOPIC = 0.25;
    private static final double SUPPORT_TOPIC = 0.45;

    private ConflictGraph() {
    }

    public static List<GraphEdge> build(Plan plan, List<SolverOutput> solvers) {
        List<GraphEdge> edges = new ArrayList<>();
        Map<Integer, SolverOutput> byClaim = new HashMap<>();
        for (SolverOutput s : solvers) {
            byClaim.put(s.claimId(), s);
        }

        // Planner-declared dependencies.
        for (Claim c : plan.claims()) {
            if (c.dependsOn() != null) {
                for (int dep : c.dependsOn()) {
                    edges.add(new GraphEdge(dep, c.id(), GraphEdge.DEPENDS, 0.5));
                }
            }
        }

        // Pairwise semantic contradiction / support between solver conclusions.
        List<Claim> claims = plan.claims();
        for (int i = 0; i < claims.size(); i++) {
            for (int j = i + 1; j < claims.size(); j++) {
                SolverOutput a = byClaim.get(claims.get(i).id());
                SolverOutput b = byClaim.get(claims.get(j).id());
                if (a == null || b == null) {
                    continue;
                }
                double topic = TextVectors.cosine(
                        claims.get(i).statement() + " " + a.conclusion(),
                        claims.get(j).statement() + " " + b.conclusion());
                if (topic < RELATED_TOPIC) {
                    continue; // unrelated claims can neither support nor contradict
                }
                DecisionPolarity.IntentResult intent =
                        DecisionPolarity.consistency(a.conclusion(), b.conclusion());
                if (intent.reversed()) {
                    edges.add(new GraphEdge(claims.get(i).id(), claims.get(j).id(),
                            GraphEdge.CONTRADICTS, Math.min(1.0, topic + 0.3)));
                } else if (intent.consistent() && topic >= SUPPORT_TOPIC) {
                    edges.add(new GraphEdge(claims.get(i).id(), claims.get(j).id(),
                            GraphEdge.SUPPORTS, topic));
                }
            }
        }
        return edges;
    }
}
