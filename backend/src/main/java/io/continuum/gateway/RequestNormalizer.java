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
                messages.add(new Message(parseRole(m.role()), m.content(), null));
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
        return new LlmRequest(requestedModel, messages, maxTokens, temperature);
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
