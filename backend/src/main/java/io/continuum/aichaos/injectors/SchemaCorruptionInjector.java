package io.continuum.aichaos.injectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Breaks structured outputs the way real models do under pressure: dropping a
 * required field, flipping a value's type, or returning malformed JSON. Tests
 * whether downstream parsing/activities handle bad structured output.
 */
@Component
public class SchemaCorruptionInjector {

    private final ObjectMapper mapper;

    public SchemaCorruptionInjector(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String corrupt(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        try {
            JsonNode node = trimmed.startsWith("{") ? mapper.readTree(trimmed) : null;
            if (node == null || !node.isObject() || node.isEmpty()) {
                // Not JSON — emit malformed JSON to simulate a broken structured response.
                return "{\"corrupted\": true, " + content;
            }
            ObjectNode obj = (ObjectNode) node;
            String firstField = obj.fieldNames().next();
            switch (ThreadLocalRandom.current().nextInt(3)) {
                case 0 -> obj.remove(firstField);                       // missing field
                case 1 -> obj.put(firstField, "__WRONG_TYPE__");        // wrong type
                default -> {
                    return mapper.writeValueAsString(obj).replaceFirst("}\\s*$", ""); // malformed (unterminated)
                }
            }
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{malformed:" + content; // guaranteed-invalid JSON
        }
    }
}
