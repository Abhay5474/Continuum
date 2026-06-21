package io.continuum.provider.model;

import java.util.List;

/**
 * A provider-agnostic completion request. {@code model} may be null, in which
 * case each adapter substitutes its configured default.
 */
public record LlmRequest(String model, List<Message> messages, Integer maxTokens, Double temperature) {

    public static LlmRequest of(List<Message> messages) {
        return new LlmRequest(null, messages, 1024, 0.2);
    }
}
