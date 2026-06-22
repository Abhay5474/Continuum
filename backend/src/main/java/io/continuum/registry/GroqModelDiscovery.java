package io.continuum.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.config.LlmProperties;
import io.continuum.provider.HttpJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groq model discovery. Curated vetted catalog plus best-effort live read of the
 * OpenAI-compatible {@code /models} endpoint when a key is configured.
 */
@Component
public class GroqModelDiscovery implements ModelDiscoveryProvider {

    private static final Logger log = LoggerFactory.getLogger(GroqModelDiscovery.class);
    private static final int CTX = 128_000;

    private final LlmProperties props;
    private final HttpJson http;

    public GroqModelDiscovery(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.http = new HttpJson(mapper);
    }

    @Override
    public String provider() {
        return "groq";
    }

    @Override
    public List<DiscoveredModel> discover() {
        Map<String, DiscoveredModel> models = new LinkedHashMap<>();
        add(models, "llama-3.3-70b-versatile", new ModelCapabilities(CTX, false, true, true, 0.00005, 0.00008, "versatile"), true);
        add(models, "llama-3.1-8b-instant", new ModelCapabilities(CTX, false, true, true, 0.00001, 0.00002, "instant"), true);

        String key = props.getGroq().getApiKey();
        if (key != null && !key.isBlank()) {
            try {
                // OpenAI-compatible GET /models is not modeled by HttpJson.post; tolerate absence.
                JsonNode resp = http.post(props.getGroq().getBaseUrl() + "/models",
                        Map.of(), new String[]{"Authorization", "Bearer " + key}, 10);
                for (JsonNode m : resp.path("data")) {
                    String name = m.path("id").asText("");
                    if (!name.isBlank() && !models.containsKey(name)) {
                        add(models, name, new ModelCapabilities(CTX, false, true, true, 0.00003, 0.00006, "discovered"), false);
                    }
                }
            } catch (Exception e) {
                log.debug("Groq live model discovery skipped: {}", e.getMessage());
            }
        }
        return new ArrayList<>(models.values());
    }

    private void add(Map<String, DiscoveredModel> map, String name, ModelCapabilities caps, boolean vetted) {
        map.put(name, new DiscoveredModel(provider(), name, caps, vetted));
    }
}
