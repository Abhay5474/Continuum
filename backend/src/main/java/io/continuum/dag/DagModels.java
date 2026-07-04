package io.continuum.dag;

import java.util.List;

/**
 * The typed vocabulary of the V6 Consensus DAG Engine. All of these cross the
 * activity boundary as JSON and are recorded in the immutable event log, so
 * they are plain, Jackson-friendly records.
 */
public final class DagModels {

    private DagModels() {
    }

    /** One atomic thing to solve/prove, produced by the planner. */
    public record Claim(int id, String statement, List<Integer> dependsOn, List<String> checks) {
    }

    /** The typed verification plan (Step 1 output). */
    public record Plan(String task, List<Claim> claims) {
    }

    /** A solver node's candidate reasoning for one claim (Step 2 output). */
    public record SolverOutput(int claimId, String reasoning, String conclusion, double confidence,
                               String provider, String model, int tokens, double costUsd) {
    }

    /** A verifier node's structured judgment of one claim (Step 2 output). */
    public record VerifierOutput(int claimId, String check, double validity,
                                 List<String> failureModes, List<String> evidence) {
    }

    /** One edge of the contradiction graph (Step 3). */
    public record GraphEdge(int fromClaim, int toClaim, String type, double weight) {
        public static final String CONTRADICTS = "CONTRADICTS";
        public static final String SUPPORTS = "SUPPORTS";
        public static final String DEPENDS = "DEPENDS";
    }

    /** Per-claim result of the Bayesian resolution (Step 4). */
    public record ClaimScore(int claimId, double prior, double posterior, boolean survived,
                             List<String> notes) {
    }

    /** The full resolution output (Step 4). */
    public record Aggregation(List<ClaimScore> scores, double finalConfidence,
                              String uncertainty, List<Integer> spine) {
    }

    /** The workflow's complete result — everything the trace UI needs. */
    public record DagResult(String answer, double finalConfidence, String uncertainty,
                            Plan plan, List<SolverOutput> solvers, List<VerifierOutput> verifiers,
                            List<GraphEdge> edges, Aggregation aggregation,
                            List<String> riskFlags, int totalTokens, double totalCostUsd) {
    }
}
