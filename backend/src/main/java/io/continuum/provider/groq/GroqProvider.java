package io.continuum.provider.groq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.continuum.config.LlmProperties;
import io.continuum.provider.HttpJson;
import io.continuum.provider.LlmProvider;
import io.continuum.provider.ModelUnavailableException;
import io.continuum.registry.catalog.ModelResolver;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.ImagePart;
import io.continuum.provider.model.Message;
import io.continuum.provider.model.ResponseFormat;
import io.continuum.provider.model.Role;
import io.continuum.provider.model.ToolCall;
import io.continuum.provider.model.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Groq adapter (OpenAI-compatible chat completions API). Acts as the failover
 * target when the primary provider is unavailable.
 */
@Component
public class GroqProvider implements LlmProvider {

    /** Used only when nothing is configured and the catalogue has nothing usable yet. */
    static final String FALLBACK_MODEL = "openai/gpt-oss-120b";
    private static final java.util.regex.Pattern SAFE_MODEL = java.util.regex.Pattern.compile("[A-Za-z0-9._/:-]{1,120}");

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

    /** Which model a request runs on; see {@link ModelResolver}. Absent in unit tests. */
    private ModelResolver resolver;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setModelResolver(ModelResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * The model for this request: the catalogue's choice when it has one (the
     * default for a request naming none, the replacement for a retired name),
     * else the configured model, else the built-in fallback. Never a name that
     * could change the URL it is put into.
     */
    String modelFor(LlmRequest request) {
        String model = resolver != null ? resolver.resolve(name(), request.model()) : request.model();
        if (model == null || model.isBlank()) {
            model = config.getModel() == null || config.getModel().isBlank() ? FALLBACK_MODEL : config.getModel();
        }
        if (!SAFE_MODEL.matcher(model).matches()) {
            throw new IllegalArgumentException("Not a model name: " + model);
        }
        return model;
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
        String model = modelFor(request);
        ObjectMapper m = http.mapper();
        ObjectNode body = m.createObjectNode();
        body.put("model", model);
        if (request.maxTokens() != null) body.put("max_tokens", request.maxTokens());
        if (request.temperature() != null) body.put("temperature", request.temperature());

        ArrayNode messages = body.putArray("messages");
        for (Message msg : request.messages()) {
            messages.add(message(m, msg));
        }
        // Groq speaks OpenAI's wire format, so tools, tool_choice and
        // response_format pass through unchanged. They used to be dropped here,
        // which meant a caller's function definitions never reached the model:
        // it answered in prose, and a tool-calling agent had nothing to execute.
        if (request.hasTools()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec t : request.tools()) {
                ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
                fn.put("name", t.name());
                if (t.description() != null) fn.put("description", t.description());
                fn.set("parameters", m.valueToTree(t.parameters() == null
                        ? java.util.Map.of("type", "object", "properties", java.util.Map.of())
                        : t.parameters()));
            }
            String choice = request.toolChoice();
            if (choice != null && !choice.isBlank()) {
                if (List.of("auto", "none", "required").contains(choice)) {
                    body.put("tool_choice", choice);
                } else {
                    body.putObject("tool_choice").put("type", "function")
                            .putObject("function").put("name", choice);
                }
            }
        }
        ResponseFormat format = request.responseFormat();
        if (format != null && format.isJson()) {
            ObjectNode rf = body.putObject("response_format");
            if ("json_schema".equals(format.type()) && format.schema() != null) {
                rf.put("type", "json_schema").set("json_schema", m.valueToTree(format.schema()));
            } else {
                rf.put("type", "json_object");
            }
        }

        String url = config.getBaseUrl() + "/chat/completions";
        JsonNode resp;
        try {
            resp = http.post(url, body, new String[]{"Authorization", "Bearer " + apiKey}, 30);
        } catch (HttpJson.HttpStatusException e) {
            // Said about the model rather than the request: tell the router, which tells the catalogue.
            if (io.continuum.registry.catalog.GroqCatalogClient.gone(e.status(), e.body())) {
                throw new ModelUnavailableException(name(), model, ModelUnavailableException.Reason.GONE, e);
            }
            throw e;
        }

        JsonNode msgNode = resp.path("choices").path(0).path("message");
        String text = msgNode.path("content").asText("");
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode tc : msgNode.path("tool_calls")) {
            JsonNode fn = tc.path("function");
            calls.add(new ToolCall(tc.path("id").asText(null), fn.path("name").asText(),
                    fn.path("arguments").asText("{}")));
        }
        int promptTokens = resp.path("usage").path("prompt_tokens").asInt(0);
        int completionTokens = resp.path("usage").path("completion_tokens").asInt(text.length() / 4);
        String finish = resp.path("choices").path(0).path("finish_reason").asText("stop");

        return new LlmResponse(text, calls, promptTokens, completionTokens, name(), model, finish);
    }

    /**
     * One message in OpenAI's shape.
     *
     * <p>Tool turns need more than a role and text: an assistant turn that
     * called tools carries the calls, and a tool result carries the id of the
     * call it answers. Without them a multi-turn tool conversation is malformed
     * and the provider rejects it.
     */
    static ObjectNode message(ObjectMapper m, Message msg) {
        ObjectNode mn = m.createObjectNode();
        mn.put("role", msg.role().name().toLowerCase());
        boolean calling = msg.toolCalls() != null && !msg.toolCalls().isEmpty();
        if (msg.hasImages()) {
            ArrayNode parts = mn.putArray("content");
            if (msg.content() != null && !msg.content().isBlank()) {
                parts.addObject().put("type", "text").put("text", msg.content());
            }
            for (ImagePart img : msg.images()) {
                ObjectNode iu = parts.addObject().put("type", "image_url").putObject("image_url");
                iu.put("url", img.url());
                if (img.detail() != null) iu.put("detail", img.detail());
            }
        } else if (calling && (msg.content() == null || msg.content().isBlank())) {
            mn.putNull("content");
        } else {
            mn.put("content", msg.content() == null ? "" : msg.content());
        }
        if (calling) {
            ArrayNode tcs = mn.putArray("tool_calls");
            for (ToolCall c : msg.toolCalls()) {
                ObjectNode tc = tcs.addObject().put("id", c.id()).put("type", "function");
                tc.putObject("function").put("name", c.name())
                        .put("arguments", c.argumentsJson() == null ? "{}" : c.argumentsJson());
            }
        }
        if (msg.role() == Role.TOOL && msg.toolCallId() != null) {
            mn.put("tool_call_id", msg.toolCallId());
        }
        return mn;
    }
}
