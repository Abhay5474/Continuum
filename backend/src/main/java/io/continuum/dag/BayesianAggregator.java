package io.continuum.dag;

import io.continuum.dag.DagModels.Aggregation;
import io.continuum.dag.DagModels.Claim;
import io.continuum.dag.DagModels.ClaimScore;
import io.continuum.dag.DagModels.GraphEdge;
import io.continuum.dag.DagModels.Plan;
import io.continuum.dag.DagModels.SolverOutput;
import io.continuum.dag.DagModels.VerifierOutput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 4 — the weighted resolution engine. No LLM judge: truth is computed by
 * Bayesian evidence aggregation over the structured node outputs, per the
 * V6 contract:
 *
 * <pre>P(correct) = prior_model_confidence × verifier_agreement × evidence_strength × contradiction_penalty</pre>
 *
 * Implemented soundly in log-odds space: the solver's self-confidence is the
 * prior; each verifier is an independent noisy sensor whose validity v
 * contributes a likelihood ratio v/(1−v) (v = 0.5 is exactly neutral evidence);
 * contradiction edges apply an iterative penalty proportional to the opposing
 * claim's current belief (a few damped relaxation rounds — deterministic,
 * order-independent because updates are computed from the previous round).
 * Pure and fully unit-tested; deterministic on every replay.
 */
public final class BayesianAggregator {

    private static final double CLAMP = 0.02;           // keep probabilities off 0/1
    private static final double VERIFIER_WEIGHT = 0.8;  // damping: verifiers are noisy sensors
    private static final int RELAXATION_ROUNDS = 3;
    private static final double CONTRADICTION_GAIN = 2.2;
    private static final double SUPPORT_GAIN = 0.5;
    private static final double SURVIVAL_THRESHOLD = 0.5;

    private BayesianAggregator() {
    }

    public static Aggregation aggregate(Plan plan, List<SolverOutput> solvers,
                                        List<VerifierOutput> verifiers, List<GraphEdge> edges) {
        Map<Integer, Double> logOdds = new HashMap<>();
        Map<Integer, List<String>> notes = new HashMap<>();

        // Prior: the solver's own confidence.
        for (Claim c : plan.claims()) {
            double prior = solvers.stream().filter(s -> s.claimId() == c.id())
                    .mapToDouble(SolverOutput::confidence).findFirst().orElse(0.5);
            logOdds.put(c.id(), logit(clamp(prior)));
            notes.put(c.id(), new ArrayList<>(List.of(
                    String.format("prior %.2f (solver self-confidence)", clamp(prior)))));
        }

        // Verifier evidence: independent likelihood-ratio updates.
        for (VerifierOutput v : verifiers) {
            if (!logOdds.containsKey(v.claimId())) {
                continue;
            }
            double validity = clamp(v.validity());
            double update = VERIFIER_WEIGHT * logit(validity);
            logOdds.merge(v.claimId(), update, Double::sum);
            notes.get(v.claimId()).add(String.format("%s: validity %.2f (%s)",
                    v.check(), validity, update >= 0 ? "supports" : "weakens"));
        }

        // Contradiction penalty / support boost: damped relaxation over the graph.
        for (int round = 0; round < RELAXATION_ROUNDS; round++) {
            Map<Integer, Double> current = new HashMap<>();
            logOdds.forEach((id, lo) -> current.put(id, sigmoid(lo)));
            for (GraphEdge e : edges) {
                Double pFrom = current.get(e.fromClaim());
                Double pTo = current.get(e.toClaim());
                if (pFrom == null || pTo == null) {
                    continue;
                }
                if (GraphEdge.CONTRADICTS.equals(e.type())) {
                    // Each side is penalized in proportion to the opponent's belief.
                    double penaltyTo = CONTRADICTION_GAIN * e.weight() * (pFrom - 0.5) / RELAXATION_ROUNDS;
                    double penaltyFrom = CONTRADICTION_GAIN * e.weight() * (pTo - 0.5) / RELAXATION_ROUNDS;
                    if (penaltyTo > 0) {
                        logOdds.merge(e.toClaim(), -penaltyTo, Double::sum);
                    }
                    if (penaltyFrom > 0) {
                        logOdds.merge(e.fromClaim(), -penaltyFrom, Double::sum);
                    }
                    if (round == 0) {
                        notes.get(e.toClaim()).add("contradiction penalty vs claim " + e.fromClaim());
                        notes.get(e.fromClaim()).add("contradiction penalty vs claim " + e.toClaim());
                    }
                } else if (GraphEdge.SUPPORTS.equals(e.type())) {
                    double boost = SUPPORT_GAIN * e.weight() * (pFrom - 0.5) / RELAXATION_ROUNDS;
                    if (boost > 0) {
                        logOdds.merge(e.toClaim(), boost, Double::sum);
                        if (round == 0) {
                            notes.get(e.toClaim()).add("supported by claim " + e.fromClaim());
                        }
                    }
                }
            }
        }

        // Scores, spine and global confidence.
        List<ClaimScore> scores = new ArrayList<>();
        List<Integer> spine = new ArrayList<>();
        double survivedSum = 0;
        int survivedCount = 0;
        double min = 1;
        double max = 0;
        for (Claim c : plan.claims()) {
            double prior = solvers.stream().filter(s -> s.claimId() == c.id())
                    .mapToDouble(SolverOutput::confidence).findFirst().orElse(0.5);
            double posterior = sigmoid(logOdds.get(c.id()));
            boolean survived = posterior >= SURVIVAL_THRESHOLD;
            scores.add(new ClaimScore(c.id(), clamp(prior), posterior, survived, notes.get(c.id())));
            if (survived) {
                spine.add(c.id());
                survivedSum += posterior;
                survivedCount++;
            }
            min = Math.min(min, posterior);
            max = Math.max(max, posterior);
        }
        double finalConfidence = survivedCount == 0 ? clamp(min)
                : survivedSum / survivedCount;
        String uncertainty = uncertaintyBand(scores, max - min);
        return new Aggregation(scores, finalConfidence, uncertainty, spine);
    }

    private static String uncertaintyBand(List<ClaimScore> scores, double spread) {
        long borderline = scores.stream()
                .filter(s -> s.posterior() > 0.35 && s.posterior() < 0.65).count();
        if (borderline > 0 || spread > 0.55) {
            return "HIGH";
        }
        if (spread > 0.3 || scores.stream().anyMatch(s -> !s.survived())) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static double clamp(double p) {
        return Math.max(CLAMP, Math.min(1 - CLAMP, p));
    }

    private static double logit(double p) {
        return Math.log(p / (1 - p));
    }

    private static double sigmoid(double lo) {
        return 1.0 / (1.0 + Math.exp(-lo));
    }
}
