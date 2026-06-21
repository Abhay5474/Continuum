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
}
