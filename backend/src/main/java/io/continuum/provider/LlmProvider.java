package io.continuum.provider;

import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;

/**
 * An adapter for a single LLM vendor. The workflow engine never talks to a
 * vendor SDK directly — only to this interface — so new providers can be added
 * without touching workflows or activities.
 */
public interface LlmProvider {

    /** Stable provider id, e.g. "gemini", "groq", "mock". */
    String name();

    /** Whether this provider is usable (e.g. an API key is configured). */
    boolean isAvailable();

    /** Estimated USD cost for the given token usage on the given model. */
    double estimateCost(String model, int promptTokens, int completionTokens);

    LlmResponse complete(LlmRequest request) throws Exception;

    /**
     * Complete using a per-call API key override (a developer's own key supplied
     * through the gateway). The default ignores the override and uses the
     * platform-configured key, preserving V1 behavior for any caller that doesn't
     * pass one. Adapters that support BYO keys override this.
     */
    default LlmResponse complete(LlmRequest request, String apiKeyOverride) throws Exception {
        return complete(request);
    }
}
