package io.continuum.provider.model;

import java.util.Map;

/**
 * A tool the caller is offering the model, provider-agnostic.
 *
 * <p>{@code parameters} is the raw JSON Schema the caller supplied, carried
 * through untouched. Continuum has no business validating or rewriting it: the
 * schema is a contract between the caller and the model, and a gateway that
 * "helpfully" normalises it changes what the model is told it can do.
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {
}
