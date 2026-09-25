package io.continuum.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around Jackson so the engine can (de)serialize event payloads and
 * activity inputs/outputs without sprinkling checked exceptions everywhere.
 */
@Component
public class Json {

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize value", e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            // This message reaches the console as the reason a run failed, so it
            // names the problem in the input rather than a Java class.
            String why = e instanceof com.fasterxml.jackson.core.JsonProcessingException jpe
                    ? jpe.getOriginalMessage() : e.getMessage();
            why = why == null ? "" : java.util.regex.Pattern
                    .compile("`?(?:[a-z_]\\w*\\.)+([A-Z]\\w*)(?:\\$(\\w+))?`?")
                    .matcher(why.split("\n")[0])
                    .replaceAll(m -> m.group(2) != null ? m.group(2) : m.group(1));
            // Jackson's "no String-argument constructor/factory method to
            // deserialize from String value" means, to a caller, one thing.
            var kind = java.util.regex.Pattern.compile("deserialize from (\\w+) value( \\('[^)]*'\\))?").matcher(why);
            if (why.startsWith("Cannot construct instance") && kind.find()) {
                why = "expected a JSON object, got " + kind.group(1).toLowerCase()
                        + (kind.group(2) == null ? "" : kind.group(2));
            }
            throw new IllegalStateException("The input could not be read: " + why, e);
        }
    }

    public <T> T read(String json, TypeReference<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize value", e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
