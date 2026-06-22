package io.continuum.gateway;

import java.util.List;

/** Public request/response shapes for the developer gateway. */
public final class GatewayDtos {

    private GatewayDtos() {
    }

    public record Message(String role, String content) {
    }

    /**
     * A gateway chat request. {@code model} may be "auto" (let Continuum route) or
     * a concrete model name. {@code routingMode} is optional
     * (LOW_COST/LOW_LATENCY/HIGH_QUALITY/BALANCED).
     */
    public record ChatRequest(
            String model,
            List<Message> messages,
            Integer maxTokens,
            Double temperature,
            String routingMode,
            Boolean requireVision) {
    }

    public record ChatResponse(
            String response,
            String provider,
            String model,
            long latency,
            int tokens,
            double cost,
            int failovers,
            String routingReason) {
    }
}
