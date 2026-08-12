package io.continuum.gateway.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.gateway.GatewayDtos;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translates between the OpenAI chat-completions format and Continuum's own.
 *
 * <p>Kept as its own class, and deliberately free of any dependency on the
 * gateway pipeline, because it is the piece most likely to need changing when a
 * client sends something new — and the least likely to be safe to change if it
 * were tangled up with routing.
 */
@Component
public class OpenAiTranslator {

    private final ObjectMapper json;

    public OpenAiTranslator(ObjectMapper json) {
        this.json = json;
    }

    /* ---------------------------------------------------------------- *
     * Inbound
     * ---------------------------------------------------------------- */

    public GatewayDtos.ChatRequest toGateway(OpenAiDtos.ChatCompletionRequest req) {
        List<GatewayDtos.Message> messages = new ArrayList<>();
        if (req.messages() != null) {
            for (OpenAiDtos.ChatMessage m : req.messages()) {
                messages.add(toGatewayMessage(m));
            }
        }
        return new GatewayDtos.ChatRequest(
                req.model(),
                messages,
                req.effectiveMaxTokens(),
                req.temperature(),
                req.routingMode(),
                req.requireVision(),
                req.measureUncertainty(),
                req.criticality(),
                req.deadlineMs(),
                toToolRefs(req.tools()),
                toolChoiceOf(req.toolChoice()),
                toFormatRef(req.responseFormat()),
                req.stream());
    }

    private GatewayDtos.Message toGatewayMessage(OpenAiDtos.ChatMessage m) {
        StringBuilder text = new StringBuilder();
        List<GatewayDtos.ImageRef> images = new ArrayList<>();
        flattenContent(m.content(), text, images);
        return new GatewayDtos.Message(
                m.role(),
                text.toString(),
                images.isEmpty() ? null : images,
                toToolCallRefs(m.toolCalls()),
                m.toolCallId());
    }

    /**
     * Content is a string or an array of typed parts; both arrive here.
     *
     * <p>Text parts are joined with newlines rather than concatenated: the parts
     * are separate blocks in the caller's mind, and gluing them together
     * silently changes "two paragraphs" into one run-on sentence.
     */
    @SuppressWarnings("unchecked")
    private void flattenContent(Object content, StringBuilder text, List<GatewayDtos.ImageRef> images) {
        if (content == null) {
            return;
        }
        if (content instanceof String s) {
            text.append(s);
            return;
        }
        if (content instanceof List<?> parts) {
            for (Object raw : parts) {
                if (raw instanceof String s) {
                    appendBlock(text, s);
                    continue;
                }
                if (!(raw instanceof Map)) {
                    continue;
                }
                Map<String, Object> part = (Map<String, Object>) raw;
                String type = String.valueOf(part.getOrDefault("type", ""));
                if ("text".equals(type) || "input_text".equals(type)) {
                    Object t = part.get("text");
                    if (t != null) {
                        appendBlock(text, String.valueOf(t));
                    }
                } else if ("image_url".equals(type) || "input_image".equals(type)) {
                    Object iu = part.get("image_url");
                    if (iu instanceof Map<?, ?> im) {
                        Object url = im.get("url");
                        Object detail = im.get("detail");
                        if (url != null) {
                            images.add(new GatewayDtos.ImageRef(String.valueOf(url),
                                    detail == null ? null : String.valueOf(detail)));
                        }
                    } else if (iu != null) {
                        images.add(new GatewayDtos.ImageRef(String.valueOf(iu), null));
                    }
                }
            }
            return;
        }
        text.append(String.valueOf(content));
    }

    private void appendBlock(StringBuilder text, String s) {
        if (text.length() > 0) {
            text.append('\n');
        }
        text.append(s);
    }

    private List<GatewayDtos.ToolRef> toToolRefs(List<OpenAiDtos.Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        List<GatewayDtos.ToolRef> out = new ArrayList<>();
        for (OpenAiDtos.Tool t : tools) {
            if (t != null && t.function() != null && t.function().name() != null) {
                out.add(new GatewayDtos.ToolRef(t.function().name(), t.function().description(),
                        t.function().parameters()));
            }
        }
        return out.isEmpty() ? null : out;
    }

    private List<GatewayDtos.ToolCallRef> toToolCallRefs(List<OpenAiDtos.ToolCallDto> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        List<GatewayDtos.ToolCallRef> out = new ArrayList<>();
        for (OpenAiDtos.ToolCallDto c : calls) {
            if (c != null && c.function() != null) {
                out.add(new GatewayDtos.ToolCallRef(c.id(), c.function().name(), c.function().arguments()));
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** {@code tool_choice} is a string or an object naming one function. */
    @SuppressWarnings("unchecked")
    private String toolChoiceOf(Object choice) {
        if (choice == null) {
            return null;
        }
        if (choice instanceof String s) {
            return s;
        }
        if (choice instanceof Map<?, ?> m) {
            Object fn = m.get("function");
            if (fn instanceof Map<?, ?> f && f.get("name") != null) {
                return String.valueOf(f.get("name"));
            }
        }
        return null;
    }

    private GatewayDtos.ResponseFormatRef toFormatRef(OpenAiDtos.ResponseFormat f) {
        return f == null || f.type() == null
                ? null
                : new GatewayDtos.ResponseFormatRef(f.type().toLowerCase(Locale.ROOT), f.jsonSchema());
    }

    /* ---------------------------------------------------------------- *
     * Outbound
     * ---------------------------------------------------------------- */

    public OpenAiDtos.ChatCompletion toCompletion(String id, GatewayDtos.ChatResponse r, String streamMode) {
        List<OpenAiDtos.ToolCallDto> toolCalls = parseToolCalls(r.toolCalls());
        boolean calling = toolCalls != null && !toolCalls.isEmpty();

        OpenAiDtos.ChatMessage message = new OpenAiDtos.ChatMessage(
                "assistant",
                // A tool-calling turn has no prose, and OpenAI sends null rather
                // than "" — clients branch on it.
                calling && (r.response() == null || r.response().isBlank()) ? null : r.response(),
                null,
                toolCalls,
                null);

        return new OpenAiDtos.ChatCompletion(
                id,
                "chat.completion",
                System.currentTimeMillis() / 1000,
                r.model(),
                List.of(OpenAiDtos.Choice.message(message, calling ? "tool_calls" : "stop")),
                usageOf(r),
                null,
                new OpenAiDtos.ContinuumMeta(
                        r.provider(),
                        r.routingReason(),
                        r.failovers(),
                        r.latency(),
                        r.cost(),
                        "cache".equals(r.provider()) ? Boolean.TRUE : null,
                        r.confidence(),
                        r.lowConfidence(),
                        streamMode));
    }

    /**
     * Usage, with the split reconstructed when only the total survived.
     *
     * <p>The gateway carries one token count through its pipeline. Reporting
     * that as {@code total_tokens} with zeros either side would make every
     * client's cost arithmetic wrong, so the prompt/completion split is
     * recovered where the response still has it and otherwise reported as a
     * total with the completion side zero — which is at least not a fabrication.
     */
    private OpenAiDtos.Usage usageOf(GatewayDtos.ChatResponse r) {
        int total = r.tokens();
        int prompt = r.promptTokens() != null ? r.promptTokens() : 0;
        int completion = r.completionTokens() != null ? r.completionTokens() : Math.max(0, total - prompt);
        return new OpenAiDtos.Usage(prompt, completion, total);
    }

    private List<OpenAiDtos.ToolCallDto> parseToolCalls(List<GatewayDtos.ToolCallRef> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        List<OpenAiDtos.ToolCallDto> out = new ArrayList<>();
        int i = 0;
        for (GatewayDtos.ToolCallRef c : calls) {
            String id = c.id() != null ? c.id() : "call_" + (i++);
            out.add(new OpenAiDtos.ToolCallDto(id, "function",
                    new OpenAiDtos.FunctionCall(c.name(), c.argumentsJson() == null ? "{}" : c.argumentsJson())));
        }
        return out;
    }

    /* ---------------------------------------------------------------- *
     * Streaming frames
     * ---------------------------------------------------------------- */

    /** The opening frame: role only, no content. Clients rely on it arriving first. */
    public String openingChunk(String id, String model) {
        return chunk(id, model, new OpenAiDtos.Delta("assistant", null, null), null, null);
    }

    public String contentChunk(String id, String model, String text) {
        return chunk(id, model, new OpenAiDtos.Delta(null, text, null), null, null);
    }

    public String toolCallChunk(String id, String model, int index, GatewayDtos.ToolCallRef call) {
        OpenAiDtos.StreamToolCall stc = new OpenAiDtos.StreamToolCall(
                index,
                call.id() != null ? call.id() : "call_" + index,
                "function",
                new OpenAiDtos.FunctionCall(call.name(), call.argumentsJson() == null ? "{}" : call.argumentsJson()));
        return chunk(id, model, new OpenAiDtos.Delta(null, null, List.of(stc)), null, null);
    }

    public String finalChunk(String id, String model, String finishReason,
                             OpenAiDtos.Usage usage, OpenAiDtos.ContinuumMeta meta) {
        return chunk(id, model, new OpenAiDtos.Delta(null, null, null), finishReason, usage, meta);
    }

    private String chunk(String id, String model, OpenAiDtos.Delta delta,
                         String finishReason, OpenAiDtos.Usage usage) {
        return chunk(id, model, delta, finishReason, usage, null);
    }

    private String chunk(String id, String model, OpenAiDtos.Delta delta, String finishReason,
                         OpenAiDtos.Usage usage, OpenAiDtos.ContinuumMeta meta) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("id", id);
        frame.put("object", "chat.completion.chunk");
        frame.put("created", System.currentTimeMillis() / 1000);
        frame.put("model", model);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        frame.put("choices", List.of(choice));
        if (usage != null) {
            frame.put("usage", usage);
        }
        if (meta != null) {
            frame.put("continuum", meta);
        }
        return write(frame);
    }

    public OpenAiDtos.Usage usage(GatewayDtos.ChatResponse r) {
        return usageOf(r);
    }

    public String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            // A frame that cannot be serialised must not kill the stream: the
            // caller has already received tokens and an abrupt socket close
            // looks like a network fault rather than a bug here.
            return "{\"error\":\"serialisation_failed\"}";
        }
    }
}
