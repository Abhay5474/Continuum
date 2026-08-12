package io.continuum.gateway.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * The OpenAI chat-completions wire format.
 *
 * <p>Continuum's own {@code GatewayDtos} are shaped for the console: a flat
 * response with the provider, the routing reason and the confidence on it. That
 * is the right shape for the screen and the wrong shape for an SDK, and the
 * gateway was advertising {@code /v1/chat/completions} while answering in it —
 * so an OpenAI client pointed here got a 200 with no {@code choices} array and
 * fell over on the first field it looked for.
 *
 * <p>These records are the other half: exactly what the OpenAI SDKs send and
 * expect back, so "change the base URL" is a true statement rather than an
 * aspiration.
 *
 * <p><b>Unknown fields are ignored, not rejected.</b> Clients send
 * {@code seed}, {@code user}, {@code logit_bias} and whatever shipped last
 * month; a gateway that 400s on a field it does not implement is worse than one
 * that routes the request and ignores it. Anything Continuum actually acts on
 * is named below.
 */
public final class OpenAiDtos {

    private OpenAiDtos() {
    }

    /* ---------------------------------------------------------------- *
     * Request
     * ---------------------------------------------------------------- */

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionRequest(
            String model,
            List<ChatMessage> messages,
            @JsonProperty("max_tokens") Integer maxTokens,
            /** The newer name for the same thing; o-series models use it. */
            @JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
            Double temperature,
            @JsonProperty("top_p") Double topP,
            Boolean stream,
            @JsonProperty("stream_options") StreamOptions streamOptions,
            List<Tool> tools,
            /** "none" | "auto" | "required" | {"type":"function","function":{"name":…}} */
            @JsonProperty("tool_choice") Object toolChoice,
            @JsonProperty("response_format") ResponseFormat responseFormat,
            Object stop,
            Integer n,

            /* ---- Continuum extensions -----------------------------------
               Namespaced under continuum_* so they cannot ever collide with a
               field OpenAI adds later. The console's own client sends the
               unprefixed GatewayDtos shape instead. */
            @JsonProperty("continuum_routing_mode") String routingMode,
            @JsonProperty("continuum_criticality") String criticality,
            @JsonProperty("continuum_deadline_ms") Long deadlineMs,
            @JsonProperty("continuum_measure_uncertainty") Boolean measureUncertainty,
            @JsonProperty("continuum_require_vision") Boolean requireVision) {

        /** {@code max_completion_tokens} wins when both are present, as upstream does. */
        public Integer effectiveMaxTokens() {
            return maxCompletionTokens != null ? maxCompletionTokens : maxTokens;
        }

        public boolean wantsStream() {
            return Boolean.TRUE.equals(stream);
        }

        public boolean wantsUsageInStream() {
            return streamOptions != null && Boolean.TRUE.equals(streamOptions.includeUsage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {
    }

    /**
     * A message.
     *
     * <p>{@code content} is deliberately {@code Object}: the API allows either a
     * string or an array of typed parts, and a client sending a vision request
     * sends the array. Typing it as String made those requests deserialise to
     * null and the model receive an empty turn.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatMessage(
            String role,
            Object content,
            String name,
            @JsonProperty("tool_calls") List<ToolCallDto> toolCalls,
            @JsonProperty("tool_call_id") String toolCallId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContentPart(
            String type,
            String text,
            @JsonProperty("image_url") ImageUrl imageUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImageUrl(String url, String detail) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tool(String type, FunctionDef function) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionDef(String name, String description, Map<String, Object> parameters) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResponseFormat(
            String type,
            @JsonProperty("json_schema") Map<String, Object> jsonSchema) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallDto(String id, String type, FunctionCall function) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionCall(String name, String arguments) {
    }

    /* ---------------------------------------------------------------- *
     * Response
     * ---------------------------------------------------------------- */

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatCompletion(
            String id,
            String object,
            long created,
            String model,
            List<Choice> choices,
            Usage usage,
            @JsonProperty("system_fingerprint") String systemFingerprint,
            /** Everything the console shows that the OpenAI shape has no room for. */
            @JsonProperty("continuum") ContinuumMeta continuumMeta) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Choice(
            int index,
            ChatMessage message,
            Delta delta,
            @JsonProperty("finish_reason") String finishReason,
            Object logprobs) {

        public static Choice message(ChatMessage m, String finishReason) {
            return new Choice(0, m, null, finishReason, null);
        }

        public static Choice delta(Delta d, String finishReason) {
            return new Choice(0, null, d, finishReason, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(
            String role,
            String content,
            @JsonProperty("tool_calls") List<StreamToolCall> toolCalls) {
    }

    /** A tool call arriving in pieces, which is how the streaming API sends them. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StreamToolCall(int index, String id, String type, FunctionCall function) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens) {
    }

    /**
     * The reliability facts, on the response.
     *
     * <p>Under its own key so the payload stays a valid ChatCompletion for any
     * strict client, while a caller who wants to know that their request failed
     * over twice and was answered by a different provider than they asked for
     * can still find out — without making a second call to a different API.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContinuumMeta(
            String provider,
            @JsonProperty("routing_reason") String routingReason,
            int failovers,
            @JsonProperty("latency_ms") long latencyMs,
            @JsonProperty("cost_usd") double costUsd,
            Boolean cached,
            Double confidence,
            @JsonProperty("low_confidence") Boolean lowConfidence,
            /**
             * Whether the stream was true passthrough or buffered.
             *
             * <p>Buffered when a post-generation feature is enabled: an answer
             * the quality gate might replace cannot be streamed before the gate
             * has seen it. Saying so is the difference between a slow first
             * token and an apparently broken one.
             */
            @JsonProperty("stream_mode") String streamMode) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorEnvelope(ApiError error) {

        public static ErrorEnvelope of(String type, String message, String code) {
            return new ErrorEnvelope(new ApiError(message, type, code, null));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiError(String message, String type, String code, String param) {
    }
}
