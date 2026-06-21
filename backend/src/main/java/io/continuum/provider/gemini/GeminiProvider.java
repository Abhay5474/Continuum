package io.continuum.provider.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.continuum.chaos.ChaosMonkey;
import io.continuum.config.LlmProperties;
import io.continuum.provider.HttpJson;
import io.continuum.provider.LlmProvider;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import io.continuum.provider.model.Role;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Google Gemini adapter. Treated as the "primary" provider in demos, so it also
 * honors the chaos toggle that forces it down to exercise failover.
 */
@Component
public class GeminiProvider implements LlmProvider {

    private final LlmProperties.Provider config;
    private final HttpJson http;
    private final ChaosMonkey chaos;

    public GeminiProvider(LlmProperties props, ObjectMapper mapper, ChaosMonkey chaos) {
        this.config = props.getGemini();
        this.http = new HttpJson(mapper);
        this.chaos = chaos;
    }

    @Override
    public String name() {
        return "gemini";
    }

    @Override
    public boolean isAvailable() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    @Override
    public double estimateCost(String model, int promptTokens, int completionTokens) {
        // gemini-3.5-flash approx pricing per 1K tokens.
        return promptTokens / 1000.0 * 0.000075 + completionTokens / 1000.0 * 0.0003;
    }

    @Override
    public LlmResponse complete(LlmRequest request) throws Exception {
        if (chaos.isPrimaryProviderDown()) {
            throw new RuntimeException("CHAOS: Gemini is down");
        }
        String model = request.model() != null ? request.model() : config.getModel();
        ObjectMapper m = http.mapper();
        ObjectNode body = m.createObjectNode();

        ArrayNode contents = body.putArray("contents");
        StringBuilder system = new StringBuilder();
        for (Message msg : request.messages()) {
            if (msg.role() == Role.SYSTEM) {
                system.append(msg.content()).append("\n");
                continue;
            }
            ObjectNode c = contents.addObject();
            c.put("role", msg.role() == Role.ASSISTANT ? "model" : "user");
            c.putArray("parts").addObject().put("text", msg.content());
        }
        if (!system.isEmpty()) {
            ObjectNode si = body.putObject("systemInstruction");
            si.putArray("parts").addObject().put("text", system.toString().trim());
        }
        ObjectNode genConfig = body.putObject("generationConfig");
        if (request.maxTokens() != null) genConfig.put("maxOutputTokens", request.maxTokens());
        if (request.temperature() != null) genConfig.put("temperature", request.temperature());

        String url = config.getBaseUrl() + "/v1beta/models/" + model + ":generateContent?key=" + config.getApiKey();
        JsonNode resp = http.post(url, body, new String[]{}, 30);

        String text = resp.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        int promptTokens = resp.path("usageMetadata").path("promptTokenCount").asInt(estimateTokens(request));
        int completionTokens = resp.path("usageMetadata").path("candidatesTokenCount").asInt(text.length() / 4);
        String finish = resp.path("candidates").path(0).path("finishReason").asText("STOP");

        return new LlmResponse(text, List.of(), promptTokens, completionTokens, name(), model, finish);
    }

    private int estimateTokens(LlmRequest request) {
        int chars = request.messages().stream().mapToInt(msg -> msg.content() == null ? 0 : msg.content().length()).sum();
        return chars / 4;
    }
}
