package io.continuum.provider.model;

/** A provider-agnostic tool/function invocation requested by the model. */
public record ToolCall(String id, String name, String argumentsJson) {
}
