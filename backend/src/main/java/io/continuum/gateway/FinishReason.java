package io.continuum.gateway;

import java.util.Locale;

/**
 * Maps each provider's reason for stopping onto OpenAI's four values.
 *
 * <p>Every provider has its own vocabulary: Gemini says {@code MAX_TOKENS} and
 * {@code SAFETY}, Anthropic says {@code end_turn} and {@code max_tokens}, OpenAI
 * and Groq say {@code length}. A caller written against the OpenAI SDK branches
 * on exactly {@code stop}, {@code length}, {@code tool_calls} and
 * {@code content_filter}, and anything else falls through its checks.
 *
 * <p>The one that matters is {@code length}. It is how a caller learns an answer
 * was cut off, and before this every answer was reported as {@code stop} —
 * including the truncated ones.
 */
public final class FinishReason {

    public static final String STOP = "stop";
    public static final String LENGTH = "length";
    public static final String TOOL_CALLS = "tool_calls";
    public static final String CONTENT_FILTER = "content_filter";

    private FinishReason() {
    }

    /**
     * @param raw     what the provider reported, in whatever case it used
     * @param calling whether the answer carries tool calls
     */
    public static String normalize(String raw, boolean calling) {
        // A turn that asks for tools is a tool turn whatever the provider called
        // it: Gemini reports STOP on a function call, and a client waiting for
        // tool_calls would treat the call as a finished answer.
        if (calling) {
            return TOOL_CALLS;
        }
        if (raw == null || raw.isBlank()) {
            return STOP;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "length", "max_tokens", "max_output_tokens", "model_length" -> LENGTH;
            case "content_filter", "safety", "recitation", "blocklist", "prohibited_content",
                 "spii", "image_safety", "language" -> CONTENT_FILTER;
            case "tool_calls", "function_call", "tool_use" -> TOOL_CALLS;
            // stop, end_turn, stop_sequence, finish_reason_unspecified, and
            // anything new: a finished answer is the safe reading of an
            // unrecognised reason, since the text itself is intact.
            default -> STOP;
        };
    }
}
