package io.continuum.routing;

import io.continuum.persistence.entity.ProviderStatsEntity;
import io.continuum.provider.ProviderRouter;
import io.continuum.provider.model.LlmRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Extension 2 — the Cost/Latency/Quality-aware scheduler.
 *
 * Operates strictly <em>above</em> the provider adapter layer: it decides the
 * order in which providers should be tried for a given task, then hands that
 * ordered chain to the existing {@link ProviderRouter} (whose failover semantics
 * are unchanged). Provider adapters are never modified.
 */
@Service
public class ProviderSelectionEngine {

    private final ProviderRouter router;
    private final ProviderMetrics metrics;
    private final TaskComplexityEstimator complexityEstimator;
    private final CostPredictor costPredictor;
    private final QualityPredictor qualityPredictor;
    private final ProviderScorer scorer;

    public ProviderSelectionEngine(ProviderRouter router, ProviderMetrics metrics,
                                   TaskComplexityEstimator complexityEstimator, CostPredictor costPredictor,
                                   QualityPredictor qualityPredictor, ProviderScorer scorer) {
        this.router = router;
        this.metrics = metrics;
        this.complexityEstimator = complexityEstimator;
        this.costPredictor = costPredictor;
        this.qualityPredictor = qualityPredictor;
        this.scorer = scorer;
    }

    public SelectionResult select(LlmRequest request, RoutingPolicy policy) {
        List<String> available = router.availableChain();
        TaskComplexityEstimator.Estimate complexity = complexityEstimator.estimate(request);

        List<ProviderScorer.Candidate> candidates = new ArrayList<>();
        for (String provider : available) {
            ProviderStatsEntity stats = metrics.get(provider);
            double cost = costPredictor.predict(provider, complexity.approxPromptTokens(), stats);
            QualityPredictor.Quality q = qualityPredictor.predict(provider, stats);
            candidates.add(new ProviderScorer.Candidate(
                    provider, cost, stats.getAvgLatencyMs(), q.score(), q.confidence(), stats.getCalls() > 0));
        }

        List<ProviderScore> scores = scorer.score(candidates, policy, complexity.complexity());

        List<String> chosen = new ArrayList<>();
        for (ProviderScore s : scores) {
            if (!s.disqualified()) {
                chosen.add(s.provider());
            }
        }
        if (chosen.isEmpty()) {
            // Constraints excluded everyone — never fail to route; fall back to availability order.
            chosen = available;
        }

        String explanation = "mode=" + policy.mode() + ", complexity=" + round(complexity.complexity())
                + " -> chain " + chosen;
        return new SelectionResult(policy.mode(), complexity.complexity(),
                complexity.approxPromptTokens(), chosen, scores, explanation);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
