package io.continuum.gateway;

import io.continuum.gateway.health.ProviderHealthTracker;
import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.entity.ModelEntity;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import io.continuum.admission.AdmissionService;
import io.continuum.admission.CostAdmissionService;
import io.continuum.admission.CostAwareLimiter;
import io.continuum.admission.Criticality;
import io.continuum.scheduling.DeadlineScheduler;
import io.continuum.scheduling.SchedulerService;
import io.continuum.provider.ProviderRouter;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import io.continuum.provider.model.LlmResponse;
import io.continuum.registry.ModelRegistryService;
import io.continuum.routing.ProviderSelectionEngine;
import io.continuum.routing.RoutingMode;
import io.continuum.routing.RoutingPolicy;
import io.continuum.routing.SelectionResult;
import io.continuum.routing.TaskComplexityEstimator;
import io.continuum.vault.CredentialVaultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small pure helpers shared by the gateway's stages. */
public final class GatewaySupport {

    private GatewaySupport() {
    }
    /**
     * A gateway response carrying everything the provider said beyond the text:
     * the tool calls, the prompt/completion split, and why it stopped.
     *
     * <p>One definition used by every path that calls a provider — direct,
     * cascaded and hedged. Before this, only the direct path carried tool calls,
     * so the same request answered differently depending on which routing mode
     * happened to serve it.
     */
    public static GatewayDtos.ChatResponse completed(GatewayDtos.ChatResponse r, LlmResponse resp) {
        if (resp == null) {
            return r;
        }
        boolean calling = resp.toolCalls() != null && !resp.toolCalls().isEmpty();
        return r.withCompletion(toToolCallRefs(resp.toolCalls()), resp.promptTokens(), resp.completionTokens())
                .withFinishReason(FinishReason.normalize(resp.finishReason(), calling));
    }
    /**
     * The provider's tool calls, in the gateway's own shape.
     *
     * <p>Null for the ordinary prose answer, so downstream can branch on "the
     * model asked for a tool" without inspecting an empty list.
     */
    public static java.util.List<GatewayDtos.ToolCallRef> toToolCallRefs(
            java.util.List<io.continuum.provider.model.ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        java.util.List<GatewayDtos.ToolCallRef> out = new java.util.ArrayList<>();
        for (io.continuum.provider.model.ToolCall c : calls) {
            out.add(new GatewayDtos.ToolCallRef(c.id(), c.name(), c.argumentsJson()));
        }
        return out;
    }
    public static Map<String, String> keyFor(Map<String, String> devKeys, String provider) {
        return devKeys.containsKey(provider) ? Map.of(provider, devKeys.get(provider)) : null;
    }
    public static String lastUserContent(LlmRequest canonical) {
        for (int i = canonical.messages().size() - 1; i >= 0; i--) {
            var m = canonical.messages().get(i);
            if ("user".equalsIgnoreCase(m.role().name())) {
                return m.content();
            }
        }
        return null;
    }

    /**
     * What a verification run is asked: the last user message, with the
     * conversation before it when there is one.
     *
     * <p>The verification engine used to be given only the last user message,
     * so a follow-up ("and what about a bird?") was verified with no idea what
     * came before, and the system prompt was dropped as well.
     */
    public static String conversationPrompt(LlmRequest canonical) {
        String last = lastUserContent(canonical);
        if (!hasEarlierTurns(canonical)) {
            return last;
        }
        return transcript(canonical, 12, 6000)
                + "\n\nAnswer the last user message, using the conversation above as context.";
    }

    /**
     * The key a cached answer is filed under, or null when the request must not
     * be answered from the cache.
     *
     * <p>It used to be the last user message alone, so the same follow-up in two
     * different conversations ("and for a bird?") got whichever answer was stored
     * first. The whole conversation is the key now. Requests with tools or images
     * are not cached: the answer depends on what is not in the text.
     */
    public static String cacheKeyOf(LlmRequest canonical) {
        if (canonical.tools() != null && !canonical.tools().isEmpty()) {
            return null;
        }
        for (Message m : canonical.messages()) {
            if (m.images() != null && !m.images().isEmpty()) {
                return null;
            }
        }
        if (!hasEarlierTurns(canonical)) {
            return lastUserContent(canonical);
        }
        return transcript(canonical, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /** A system prompt or an earlier turn before the last user message. */
    static boolean hasEarlierTurns(LlmRequest canonical) {
        int users = 0;
        for (Message m : canonical.messages()) {
            if (m.role() == io.continuum.provider.model.Role.USER) {
                users++;
            } else {
                return true;
            }
        }
        return users > 1;
    }

    /** The last {@code maxMessages} messages as "role: text" lines, newest kept when over {@code maxChars}. */
    static String transcript(LlmRequest canonical, int maxMessages, int maxChars) {
        List<Message> all = canonical.messages();
        StringBuilder out = new StringBuilder();
        Message system = all.stream().filter(m -> m.role() == io.continuum.provider.model.Role.SYSTEM).findFirst().orElse(null);
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (Message m : all) {
            if (m.role() == io.continuum.provider.model.Role.SYSTEM || m.content() == null || m.content().isBlank()) {
                continue;
            }
            lines.add(m.role().name().toLowerCase() + ": " + m.content().trim());
        }
        int from = Math.max(0, lines.size() - maxMessages);
        java.util.List<String> kept = new java.util.ArrayList<>(lines.subList(from, lines.size()));
        int size = kept.stream().mapToInt(l -> l.length() + 1).sum();
        while (kept.size() > 1 && size > maxChars) {
            size -= kept.remove(0).length() + 1;
        }
        if (system != null && system.content() != null && !system.content().isBlank()) {
            out.append("system: ").append(system.content().trim()).append('\n');
        }
        out.append(String.join("\n", kept));
        return out.toString();
    }
}
