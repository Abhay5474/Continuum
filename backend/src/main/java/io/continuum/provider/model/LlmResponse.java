package io.continuum.provider.model;

import java.util.List;

/**
 * A provider-agnostic completion result. Token counts feed cost tracking;
 * {@code provider} records which adapter actually served the request (important
 * when failover kicked in).
 */
public record LlmResponse(String content, List<ToolCall> toolCalls, int promptTokens,
                          int completionTokens, String provider, String model, String finishReason) {
}
