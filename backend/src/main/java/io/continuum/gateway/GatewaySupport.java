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
}
