package io.continuum.provider.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.continuum.chaos.ChaosMonkey;
import io.continuum.config.LlmProperties;
import io.continuum.provider.HttpJson;
import io.continuum.provider.LlmProvider;
import io.continuum.provider.model.ImagePart;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import io.continuum.provider.model.ResponseFormat;
import io.continuum.provider.model.Role;
import io.continuum.provider.model.ToolCall;
import io.continuum.provider.model.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Google Gemini adapter. Treated as the "primary" provider in demos, so it also
 * honors the chaos toggle that forces it down to exercise failover.
 *
 * <p>The gateway speaks OpenAI's shape and Gemini does not, so this is a
 * translation, not a pass-through. Tools become function declarations, a tool
 * result becomes a {@code functionResponse} part, images become inline data, and
 * JSON mode becomes a response MIME type. The first version sent text only:
 * tools, images and JSON mode were dropped without a word, so a tool-calling
 * client got prose back from Gemini and a function call back from the mock, and
 * could not tell which provider had served it.
 */
@Component
public class GeminiProvider implements LlmProvider {

    /**
     * JSON Schema keywords Gemini's OpenAPI-subset schema rejects outright.
     * OpenAI clients send them routinely — {@code additionalProperties: false}
     * is required for OpenAI strict mode — and one of them fails the whole call.
     */
    private static final Set<String> UNSUPPORTED_SCHEMA_KEYS =
            Set.of("$schema", "$id", "additionalProperties", "strict", "$defs", "definitions", "$ref");

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
        return complete(request, null);
    }

    @Override
    public LlmResponse complete(LlmRequest request, String apiKeyOverride) throws Exception {
        if (chaos.isPrimaryProviderDown()) {
            throw new RuntimeException("CHAOS: Gemini is down");
        }
        String apiKey = (apiKeyOverride != null && !apiKeyOverride.isBlank()) ? apiKeyOverride : config.getApiKey();
        String model = request.model() != null ? request.model() : config.getModel();

        // The key goes in a header. On the query string it is part of the URL,
        // and a URL is what ends up in exception messages and access logs.
        String url = config.getBaseUrl() + "/v1beta/models/" + model + ":generateContent";
        JsonNode resp = http.post(url, buildBody(http.mapper(), request),
                new String[]{"x-goog-api-key", apiKey}, 30);
        return parse(resp, request, model);
    }

    /** The request body, separate from the call so the translation is testable without a key. */
    public ObjectNode buildBody(ObjectMapper m, LlmRequest request) {
        ObjectNode body = m.createObjectNode();
        ArrayNode contents = body.putArray("contents");
        StringBuilder system = new StringBuilder();

        // Gemini's functionResponse is matched to its call by name, not id, and
        // a tool-result message carries only the id. Recover the name from the
        // assistant turn that made the call.
        Map<String, String> callNames = new HashMap<>();
        for (Message msg : request.messages()) {
            if (msg.toolCalls() != null) {
                for (ToolCall c : msg.toolCalls()) {
                    if (c.id() != null) {
                        callNames.put(c.id(), c.name());
                    }
                }
            }
        }

        for (Message msg : request.messages()) {
            if (msg.role() == Role.SYSTEM) {
                system.append(msg.content()).append("\n");
                continue;
            }
            ObjectNode c = contents.addObject();
            ArrayNode parts = c.putArray("parts");
            if (msg.role() == Role.TOOL) {
                c.put("role", "user");
                ObjectNode fr = parts.addObject().putObject("functionResponse");
                fr.put("name", callNames.getOrDefault(msg.toolCallId(), "tool"));
                fr.set("response", toolResult(m, msg.content()));
                continue;
            }
            c.put("role", msg.role() == Role.ASSISTANT ? "model" : "user");
            if (msg.content() != null && !msg.content().isBlank()) {
                parts.addObject().put("text", msg.content());
            }
            if (msg.hasImages()) {
                for (ImagePart img : msg.images()) {
                    parts.add(inlineImage(m, img));
                }
            }
            if (msg.toolCalls() != null) {
                for (ToolCall call : msg.toolCalls()) {
                    ObjectNode fc = parts.addObject().putObject("functionCall");
                    fc.put("name", call.name());
                    fc.set("args", parseArgs(m, call.argumentsJson()));
                }
            }
            if (parts.isEmpty()) {
                // Gemini rejects a content with no parts.
                parts.addObject().put("text", "");
            }
        }
        if (!system.isEmpty()) {
            ObjectNode si = body.putObject("systemInstruction");
            si.putArray("parts").addObject().put("text", system.toString().trim());
        }

        if (request.hasTools()) {
            ArrayNode decls = body.putArray("tools").addObject().putArray("functionDeclarations");
            for (ToolSpec t : request.tools()) {
                ObjectNode d = decls.addObject();
                d.put("name", t.name());
                if (t.description() != null) {
                    d.put("description", t.description());
                }
                if (t.parameters() != null && !t.parameters().isEmpty()) {
                    d.set("parameters", m.valueToTree(geminiSchema(t.parameters())));
                }
            }
            String choice = request.toolChoice();
            if (choice != null && !choice.isBlank() && !"auto".equals(choice)) {
                ObjectNode fcc = body.putObject("toolConfig").putObject("functionCallingConfig");
                switch (choice) {
                    case "none" -> fcc.put("mode", "NONE");
                    case "required" -> fcc.put("mode", "ANY");
                    default -> {
                        // A named function: ANY, restricted to that one.
                        fcc.put("mode", "ANY");
                        fcc.putArray("allowedFunctionNames").add(choice);
                    }
                }
            }
        }

        ObjectNode genConfig = body.putObject("generationConfig");
        if (request.maxTokens() != null) genConfig.put("maxOutputTokens", request.maxTokens());
        if (request.temperature() != null) genConfig.put("temperature", request.temperature());
        ResponseFormat format = request.responseFormat();
        if (format != null && format.isJson()) {
            genConfig.put("responseMimeType", "application/json");
            if (format.schema() != null && format.schema().get("schema") instanceof Map<?, ?> schema) {
                genConfig.set("responseSchema", m.valueToTree(geminiSchema(schema)));
            }
        }
        return body;
    }

    /** The response, separate from the call for the same reason as {@link #buildBody}. */
    public LlmResponse parse(JsonNode resp, LlmRequest request, String model) {
        JsonNode candidate = resp.path("candidates").path(0);
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        int i = 0;
        for (JsonNode part : candidate.path("content").path("parts")) {
            // A thinking model can return its reasoning as parts marked thought.
            // It is not the answer and must not be shown as one.
            if (part.path("thought").asBoolean(false)) {
                continue;
            }
            // Every text part, not the first: a long answer can arrive split
            // across several, and reading parts[0] silently truncated it.
            if (part.has("text")) {
                text.append(part.path("text").asText(""));
            }
            if (part.has("functionCall")) {
                JsonNode fc = part.path("functionCall");
                String id = fc.path("id").asText("");
                calls.add(new ToolCall(id.isBlank() ? "call_" + i : id,
                        fc.path("name").asText(), fc.path("args").isMissingNode()
                                ? "{}" : fc.path("args").toString()));
                i++;
            }
        }
        int promptTokens = resp.path("usageMetadata").path("promptTokenCount").asInt(estimateTokens(request));
        int completionTokens = resp.path("usageMetadata").path("candidatesTokenCount").asInt(text.length() / 4);
        // A prompt Gemini refuses comes back with no candidates and the reason
        // on promptFeedback. Reported as SAFETY so the gateway says
        // content_filter, rather than returning an empty answer as a success.
        String finish = candidate.isMissingNode() && resp.path("promptFeedback").has("blockReason")
                ? "SAFETY"
                : candidate.path("finishReason").asText("STOP");
        return new LlmResponse(text.toString(), calls, promptTokens, completionTokens, name(), model, finish);
    }

    /**
     * An OpenAI-style JSON Schema, minus the keywords Gemini rejects.
     * Recursive, because the offending keys usually sit on nested objects.
     */
    @SuppressWarnings("unchecked")
    static Object geminiSchema(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (UNSUPPORTED_SCHEMA_KEYS.contains(key)) {
                    continue;
                }
                out.put(key, geminiSchema(e.getValue()));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object o : list) {
                out.add(geminiSchema(o));
            }
            return out;
        }
        return node;
    }

    /**
     * An image as an inline part.
     *
     * <p>Only data URLs can be sent inline. A remote URL is refused with a
     * message rather than fetched here: fetching an arbitrary caller-supplied URL
     * from inside the gateway is a server-side request forgery surface, and the
     * refusal makes the router fail over to a provider that accepts URLs.
     */
    private static ObjectNode inlineImage(ObjectMapper m, ImagePart img) {
        String url = img.url() == null ? "" : img.url();
        if (!url.startsWith("data:")) {
            throw new IllegalArgumentException("Gemini accepts images as inline data only; "
                    + "send the image as a data: URL rather than a link.");
        }
        int comma = url.indexOf(',');
        int semi = url.indexOf(';');
        String mime = semi > 5 && semi < comma ? url.substring(5, semi) : "image/png";
        ObjectNode part = m.createObjectNode();
        part.putObject("inlineData").put("mimeType", mime).put("data", url.substring(comma + 1));
        return part;
    }

    /** Tool arguments arrive as a JSON string; Gemini wants the object. */
    private static JsonNode parseArgs(ObjectMapper m, String json) {
        try {
            JsonNode n = m.readTree(json == null || json.isBlank() ? "{}" : json);
            return n.isObject() ? n : m.createObjectNode();
        } catch (Exception e) {
            return m.createObjectNode();
        }
    }

    /**
     * A tool's output as Gemini's {@code response} object.
     *
     * <p>Gemini requires an object here, and tool output is usually a string,
     * sometimes JSON. JSON objects pass through; anything else is wrapped.
     */
    private static JsonNode toolResult(ObjectMapper m, String content) {
        try {
            JsonNode n = m.readTree(content == null ? "" : content);
            if (n != null && n.isObject()) {
                return n;
            }
        } catch (Exception ignored) {
            // Not JSON: wrapped below.
        }
        ObjectNode wrapped = m.createObjectNode();
        wrapped.put("result", content == null ? "" : content);
        return wrapped;
    }

    private int estimateTokens(LlmRequest request) {
        int chars = request.messages().stream().mapToInt(msg -> msg.content() == null ? 0 : msg.content().length()).sum();
        return chars / 4;
    }
}
