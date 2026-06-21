package io.continuum.hedging;

import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import io.continuum.routing.CostPredictor;
import io.continuum.routing.ProviderMetrics;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Keeps hedging cost-aware: a hedge is only launched if the cumulative predicted
 * cost of all in-flight requests stays within the per-request budget. Prevents
 * "blindly duplicate every request" cost blow-ups.
 */
@Component
public class CostBudgetGuard {

    private final CostPredictor costPredictor;
    private final ProviderMetrics metrics;

    public CostBudgetGuard(CostPredictor costPredictor, ProviderMetrics metrics) {
        this.costPredictor = costPredictor;
        this.metrics = metrics;
    }

    public boolean canAfford(List<String> alreadyLaunched, String candidate, LlmRequest request, Double budgetUsd) {
        if (budgetUsd == null) {
            return true;
        }
        int promptTokens = approxPromptTokens(request);
        double cumulative = costPredictor.predict(candidate, promptTokens, metrics.get(candidate));
        for (String p : alreadyLaunched) {
            cumulative += costPredictor.predict(p, promptTokens, metrics.get(p));
        }
        return cumulative <= budgetUsd;
    }

    private int approxPromptTokens(LlmRequest request) {
        int chars = 0;
        for (Message m : request.messages()) {
            if (m.content() != null) {
                chars += m.content().length();
            }
        }
        return chars / 4;
    }
}
