package io.continuum.routing;

import io.continuum.config.LlmProperties;
import io.continuum.persistence.entity.ProviderStatsEntity;
import io.continuum.provider.ProviderRouter;
import org.springframework.stereotype.Component;

/**
 * Predicts the USD cost of running a task on a provider.
 *
 * Completion length is predicted from the provider's <em>measured</em> historical
 * average tokens-per-call when available (continuous learning); before any data
 * exists it falls back to a transparent default proportional to the prompt.
 */
@Component
public class CostPredictor {

    private final ProviderRouter router;
    private final LlmProperties props;

    public CostPredictor(ProviderRouter router, LlmProperties props) {
        this.router = router;
        this.props = props;
    }

    public double predict(String provider, int promptTokens, ProviderStatsEntity stats) {
        int predictedCompletion;
        if (stats != null && stats.getSuccesses() > 0) {
            double avgCompletion = (double) stats.getCompletionTokens() / stats.getSuccesses();
            predictedCompletion = (int) Math.round(avgCompletion);
        } else {
            predictedCompletion = Math.max(64, promptTokens / 4); // transparent cold-start default
        }
        String model = modelFor(provider);
        return router.estimateCost(provider, model, promptTokens, predictedCompletion);
    }

    private String modelFor(String provider) {
        return switch (provider) {
            case "gemini" -> props.getGemini().getModel();
            case "groq" -> props.getGroq().getModel();
            default -> provider;
        };
    }
}
