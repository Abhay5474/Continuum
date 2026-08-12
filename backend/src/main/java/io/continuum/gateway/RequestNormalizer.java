package io.continuum.gateway;

import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import io.continuum.provider.model.Role;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Translates a public {@link GatewayDtos.ChatRequest} into Continuum's canonical
 * {@link LlmRequest}. Provider adapters only ever see the canonical form, so the
 * gateway is provider-agnostic.
 */
@Component
public class RequestNormalizer {

    public LlmRequest normalize(GatewayDtos.ChatRequest req) {
        List<Message> messages = new ArrayList<>();
        if (req.messages() != null) {
            for (GatewayDtos.Message m : req.messages()) {
                messages.add(new Message(
                        parseRole(m.role()),
                        m.content(),
                        toToolCalls(m.toolCalls()),
                        toImages(m.images()),
                        m.toolCallId()));
            }
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        // "auto" (or null) means: let the router choose the model.
        String requestedModel = (req.model() == null || req.model().isBlank()
                || req.model().equalsIgnoreCase("auto")) ? null : req.model();
        int maxTokens = req.maxTokens() != null ? req.maxTokens() : 512;
        double temperature = req.temperature() != null ? req.temperature() : 0.2;
        return new LlmRequest(requestedModel, messages, maxTokens, temperature,
                toTools(req.tools()), req.toolChoice(), toFormat(req.responseFormat()));
    }

    private static List<io.continuum.provider.model.ToolSpec> toTools(List<GatewayDtos.ToolRef> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        List<io.continuum.provider.model.ToolSpec> out = new ArrayList<>();
        for (GatewayDtos.ToolRef t : tools) {
            if (t != null && t.name() != null) {
                out.add(new io.continuum.provider.model.ToolSpec(t.name(), t.description(), t.parameters()));
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static List<io.continuum.provider.model.ImagePart> toImages(List<GatewayDtos.ImageRef> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        List<io.continuum.provider.model.ImagePart> out = new ArrayList<>();
        for (GatewayDtos.ImageRef i : images) {
            if (i != null && i.url() != null) {
                out.add(new io.continuum.provider.model.ImagePart(i.url(), i.detail()));
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static List<io.continuum.provider.model.ToolCall> toToolCalls(List<GatewayDtos.ToolCallRef> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        List<io.continuum.provider.model.ToolCall> out = new ArrayList<>();
        for (GatewayDtos.ToolCallRef c : calls) {
            if (c != null) {
                out.add(new io.continuum.provider.model.ToolCall(c.id(), c.name(), c.argumentsJson()));
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static io.continuum.provider.model.ResponseFormat toFormat(GatewayDtos.ResponseFormatRef f) {
        return f == null || f.type() == null
                ? null
                : new io.continuum.provider.model.ResponseFormat(f.type(), f.schema());
    }

    private Role parseRole(String role) {
        if (role == null) {
            return Role.USER;
        }
        return switch (role.toLowerCase(Locale.ROOT)) {
            case "system" -> Role.SYSTEM;
            case "assistant", "model" -> Role.ASSISTANT;
            case "tool" -> Role.TOOL;
            default -> Role.USER;
        };
    }
}
