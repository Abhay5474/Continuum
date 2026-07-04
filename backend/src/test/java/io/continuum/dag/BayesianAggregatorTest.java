package io.continuum.dag;

import io.continuum.dag.DagModels.Aggregation;
import io.continuum.dag.DagModels.Claim;
import io.continuum.dag.DagModels.GraphEdge;
import io.continuum.dag.DagModels.Plan;
import io.continuum.dag.DagModels.SolverOutput;
import io.continuum.dag.DagModels.VerifierOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Step-4 weighted resolution engine: pure Bayesian math, no LLM judge. */
class BayesianAggregatorTest {

    private Plan plan(int n) {
        return new Plan("task", java.util.stream.IntStream.rangeClosed(1, n)
                .mapToObj(i -> new Claim(i, "claim " + i, List.of(), List.of("LOGIC_CONSISTENCY")))
                .toList());
    }

    private SolverOutput solver(int id, double conf) {
        return new SolverOutput(id, "reasoning", "conclusion " + id, conf, "mock", "m", 10, 0);
    }

    private VerifierOutput verifier(int id, double validity) {
        return new VerifierOutput(id, "LOGIC_CONSISTENCY", validity, List.of(), List.of());
    }

    @Test
    void verifierAgreementRaisesConfidenceAndDisagreementLowersIt() {
        Aggregation supported = BayesianAggregator.aggregate(plan(1),
                List.of(solver(1, 0.6)), List.of(verifier(1, 0.9), verifier(1, 0.85)), List.of());
        Aggregation contested = BayesianAggregator.aggregate(plan(1),
                List.of(solver(1, 0.6)), List.of(verifier(1, 0.15), verifier(1, 0.2)), List.of());

        assertTrue(supported.scores().get(0).posterior() > 0.6,
                "agreeing verifiers must raise the posterior above the prior");
        assertTrue(contested.scores().get(0).posterior() < 0.5,
                "failing verifiers must sink the claim below survival");
        assertFalse(contested.scores().get(0).survived());
        assertTrue(contested.spine().isEmpty());
    }

    @Test
    void neutralVerifierIsExactlyNoEvidence() {
        Aggregation a = BayesianAggregator.aggregate(plan(1),
                List.of(solver(1, 0.7)), List.of(verifier(1, 0.5)), List.of());
        assertEquals(0.7, a.scores().get(0).posterior(), 0.01,
                "validity 0.5 must contribute a likelihood ratio of exactly 1");
    }

    @Test
    void contradictionPenaltyResolvesTowardTheStrongerClaim() {
        // Claim 1 well-supported; claim 2 weak; they contradict.
        Aggregation a = BayesianAggregator.aggregate(plan(2),
                List.of(solver(1, 0.85), solver(2, 0.55)),
                List.of(verifier(1, 0.9), verifier(2, 0.45)),
                List.of(new GraphEdge(1, 2, GraphEdge.CONTRADICTS, 0.8)));

        var c1 = a.scores().get(0);
        var c2 = a.scores().get(1);
        assertTrue(c1.survived(), "the confidently-verified claim survives the conflict");
        assertFalse(c2.survived(), "the weaker contradicted claim is eliminated: P=" + c2.posterior());
        assertEquals(List.of(1), a.spine(), "the reasoning spine keeps only the survivor");
        assertNotEquals("LOW", a.uncertainty(), "an eliminated claim cannot be LOW uncertainty");
    }

    @Test
    void boundsAndDeterminism() {
        Aggregation a = BayesianAggregator.aggregate(plan(3),
                List.of(solver(1, 0.99), solver(2, 0.01), solver(3, 0.5)),
                List.of(verifier(1, 0.99), verifier(2, 0.01)),
                List.of(new GraphEdge(1, 2, GraphEdge.CONTRADICTS, 1.0),
                        new GraphEdge(1, 3, GraphEdge.SUPPORTS, 0.8)));
        for (var s : a.scores()) {
            assertTrue(s.posterior() > 0 && s.posterior() < 1, "posteriors stay in (0,1)");
        }
        Aggregation b = BayesianAggregator.aggregate(plan(3),
                List.of(solver(1, 0.99), solver(2, 0.01), solver(3, 0.5)),
                List.of(verifier(1, 0.99), verifier(2, 0.01)),
                List.of(new GraphEdge(1, 2, GraphEdge.CONTRADICTS, 1.0),
                        new GraphEdge(1, 3, GraphEdge.SUPPORTS, 0.8)));
        assertEquals(a.scores().toString(), b.scores().toString(), "pure math ⇒ replay-identical");
    }
}
