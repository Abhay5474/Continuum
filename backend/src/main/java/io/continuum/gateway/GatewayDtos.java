package io.continuum.gateway;

import java.util.List;

/** Public request/response shapes for the developer gateway. */
public final class GatewayDtos {

    private GatewayDtos() {
    }

    /**
     * A message on the wire.
     *
     * <p>{@code content} stays a String here — this is Continuum's own shape and
     * the console only ever sends text. The richer OpenAI form (typed content
     * parts) is translated into {@code images} by the compatibility layer, so
     * both front doors converge on the same internal request.
     */
    public record Message(String role, String content,
                          List<ImageRef> images,
                          List<ToolCallRef> toolCalls,
                          String toolCallId) {

        public Message(String role, String content) {
            this(role, content, null, null, null);
        }
    }

    /** An image attached to a message, as the caller supplied it. */
    public record ImageRef(String url, String detail) {
    }

    /** A tool call the model previously made, replayed back on the next turn. */
    public record ToolCallRef(String id, String name, String argumentsJson) {
    }

    /** A tool the caller is offering the model. */
    public record ToolRef(String name, String description, java.util.Map<String, Object> parameters) {
    }

    /** How the answer should be shaped: "text", "json_object" or "json_schema". */
    public record ResponseFormatRef(String type, java.util.Map<String, Object> schema) {
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
            Boolean measureUncertainty,
            /**
             * How much this request deserves the last free slot when a provider
             * is at capacity: BACKGROUND, NORMAL (the default) or CRITICAL.
             *
             * <p>Only consulted when admission control is on. An unrecognised
             * value reads as NORMAL rather than BACKGROUND — a typo in a client
             * must not silently make that caller's traffic the first thing
             * dropped under load.
             */
            String criticality,
            /**
             * How long this result stays useful, in milliseconds from now.
             *
             * <p>Only consulted when scheduling is on. A request that cannot meet
             * its deadline even with an immediate start is refused rather than
             * run: starting it spends a slot on a result nobody can use and
             * delays the requests that could still make theirs.
             *
             * <p>Null means no deadline, which is not the same as an urgent one —
             * an unset deadline sorts last among equal priorities rather than
             * first.
             */
            Long deadlineMs,
            /** Tools offered to the model. Carried through to the provider untouched. */
            List<ToolRef> tools,
            /** "none" | "auto" | "required" | a specific function name. */
            String toolChoice,
            /** Ask for JSON rather than prose. */
            ResponseFormatRef responseFormat,
            /**
             * Stream the answer as server-sent events.
             *
             * <p>Handled by the transport, not here: {@code GatewayService.chat}
             * always produces a complete answer, because half the features on
             * this deployment — the quality gate, confidence, the cascade —
             * cannot judge an answer they have not finished reading.
             */
            Boolean stream) {

        /** The nine-argument form every existing caller uses. */
        public ChatRequest(String model, List<Message> messages, Integer maxTokens, Double temperature,
                           String routingMode, Boolean requireVision, Boolean measureUncertainty,
                           String criticality, Long deadlineMs) {
            this(model, messages, maxTokens, temperature, routingMode, requireVision,
                    measureUncertainty, criticality, deadlineMs, null, null, null, null);
        }
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
            Integer agreementClusters,
            /**
             * Tool calls the model wants executed, when it asked for any.
             *
             * <p>Null rather than empty when the model answered in prose, so a
             * caller can tell "no tools requested" from "tools requested, none
             * parsed" — the second is a bug and must not read as the first.
             */
            List<ToolCallRef> toolCalls,
            /** The prompt/completion split, kept so usage reporting is not invented. */
            Integer promptTokens,
            Integer completionTokens) {

        /** The ordinary, unmeasured response. */
        public ChatResponse(String response, String provider, String model, long latency, int tokens,
                            double cost, int failovers, String routingReason) {
            this(response, provider, model, latency, tokens, cost, failovers, routingReason,
                    null, null, null, null, null, null);
        }

        /** The eleven-argument form the uncertainty and quality stages build. */
        public ChatResponse(String response, String provider, String model, long latency, int tokens,
                            double cost, int failovers, String routingReason,
                            Double confidence, Boolean lowConfidence, Integer agreementClusters) {
            this(response, provider, model, latency, tokens, cost, failovers, routingReason,
                    confidence, lowConfidence, agreementClusters, null, null, null);
        }

        /** The same response carrying the model's tool calls and its token split. */
        public ChatResponse withCompletion(List<ToolCallRef> calls, Integer prompt, Integer completion) {
            return new ChatResponse(response, provider, model, latency, tokens, cost, failovers,
                    routingReason, confidence, lowConfidence, agreementClusters, calls, prompt, completion);
        }
    }
}
