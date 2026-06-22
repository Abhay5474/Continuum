package io.continuum.provider.groq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.continuum.config.LlmProperties;
import io.continuum.provider.HttpJson;
import io.continuum.provider.LlmProvider;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Groq adapter (OpenAI-compatible chat completions API). Acts as the failover
 * target when the primary provider is unavailable.
 */
@Component
public class GroqProvider implements LlmProvider {

    private final LlmProperties.Provider config;
    private final HttpJson http;

    public GroqProvider(LlmProperties props, ObjectMapper mapper) {
        this.config = props.getGroq();
        this.http = new HttpJson(mapper);
    }

    @Override
    public String name() {
        return "groq";
    }

    @Override
    public boolean isAvailable() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    @Override
    public double estimateCost(String model, int promptTokens, int completionTokens) {
        return promptTokens / 1000.0 * 0.00005 + completionTokens / 1000.0 * 0.00008;
    }

    @Override
    public LlmResponse complete(LlmRequest request) throws Exception {
        return complete(request, null);
    }

    @Override
    public LlmResponse complete(LlmRequest request, String apiKeyOverride) throws Exception {
        String apiKey = (apiKeyOverride != null && !apiKeyOverride.isBlank()) ? apiKeyOverride : config.getApiKey();
        String model = request.model() != null ? request.model() : config.getModel();
        ObjectMapper m = http.mapper();
        ObjectNode body = m.createObjectNode();
        body.put("model", model);
        if (request.maxTokens() != null) body.put("max_tokens", request.maxTokens());
        if (request.temperature() != null) body.put("temperature", request.temperature());

        ArrayNode messages = body.putArray("messages");
        for (Message msg : request.messages()) {
            ObjectNode mn = messages.addObject();
            mn.put("role", msg.role().name().toLowerCase());
            mn.put("content", msg.content() == null ? "" : msg.content());
        }

        String url = config.getBaseUrl() + "/chat/completions";
        JsonNode resp = http.post(url, body,
                new String[]{"Authorization", "Bearer " + apiKey}, 30);

        String text = resp.path("choices").path(0).path("message").path("content").asText("");
        int promptTokens = resp.path("usage").path("prompt_tokens").asInt(0);
        int completionTokens = resp.path("usage").path("completion_tokens").asInt(text.length() / 4);
        String finish = resp.path("choices").path(0).path("finish_reason").asText("stop");

        return new LlmResponse(text, List.of(), promptTokens, completionTokens, name(), model, finish);
    }
}
