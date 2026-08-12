package io.continuum.provider.model;

import java.util.Map;

/**
 * How the caller wants the answer shaped: free text, any JSON, or a schema.
 *
 * @param type   "text", "json_object" or "json_schema"
 * @param schema the JSON Schema, present only for "json_schema"
 */
public record ResponseFormat(String type, Map<String, Object> schema) {

    public static final ResponseFormat TEXT = new ResponseFormat("text", null);

    public boolean isJson() {
        return type != null && type.startsWith("json");
    }
}
