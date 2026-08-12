package io.continuum.provider.model;

import java.util.List;

/**
 * One request to a model, in Continuum's own shape.
 *
 * <p>The four-argument form is kept because thirty call sites use it and none of
 * them are about tools; the canonical constructor carries what an OpenAI client
 * can actually send. Fields the providers cannot honour are passed through
 * rather than dropped — a caller who asked for a JSON object and silently got
 * prose has been lied to.
 */
public record LlmRequest(String model,
                         List<Message> messages,
                         Integer maxTokens,
                         Double temperature,
                         /** Tools offered to the model. Null when the caller offered none. */
                         List<ToolSpec> tools,
                         /** "none", "auto", "required", or a specific function name. */
                         String toolChoice,
                         ResponseFormat responseFormat) {

    /** The ordinary request: no tools, free-text answer. */
    public LlmRequest(String model, List<Message> messages, Integer maxTokens, Double temperature) {
        this(model, messages, maxTokens, temperature, null, null, null);
    }

    public static LlmRequest of(List<Message> messages) {
        return new LlmRequest(null, messages, 1024, 0.2);
    }

    /** The same request aimed at a different model — how failover re-targets. */
    public LlmRequest withModel(String newModel) {
        return new LlmRequest(newModel, messages, maxTokens, temperature, tools, toolChoice, responseFormat);
    }

    /** The same request with rewritten messages — how the firewall and the transformers edit. */
    public LlmRequest withMessages(List<Message> newMessages) {
        return new LlmRequest(model, newMessages, maxTokens, temperature, tools, toolChoice, responseFormat);
    }

    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }
}
