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
            Boolean requireVision,
            /**
             * Ask for a confidence measurement on this request. Honoured in
             * ON_DEMAND and ADAPTIVE modes; costs k times the tokens, so it is
             * opt-in per request rather than assumed.
             */
            Boolean measureUncertainty) {
    }

    public record ChatResponse(
            String response,
            String provider,
            String model,
            long latency,
            int tokens,
            double cost,
            int failovers,
            String routingReason,
            /**
             * How much the model agreed with itself, in [0,1]. Null unless
             * uncertainty was measured for this request — a caller must be able
             * to tell "confident" apart from "not measured", and a default of
             * 1.0 would quietly assert the first.
             */
            Double confidence,
            /** True when confidence fell below the account's threshold. */
            Boolean lowConfidence,
            /** Distinct meanings across the samples; 1 means full agreement. */
            Integer agreementClusters) {

        /** The ordinary, unmeasured response. */
        public ChatResponse(String response, String provider, String model, long latency, int tokens,
                            double cost, int failovers, String routingReason) {
            this(response, provider, model, latency, tokens, cost, failovers, routingReason,
                    null, null, null);
        }
    }
}
