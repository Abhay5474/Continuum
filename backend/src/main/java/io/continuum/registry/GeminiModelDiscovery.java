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
 * Gemini model discovery. Returns a vetted curated catalog of current Gemini
 * models, and — when an API key is configured — best-effort augments it from the
 * live {@code v1beta/models} endpoint (net-new models are added as unvetted, so
 * they enter the registry as DISCOVERED rather than ACTIVE).
 */
@Component
public class GeminiModelDiscovery implements ModelDiscoveryProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiModelDiscovery.class);
    private static final int CTX = 1_000_000;

    private final LlmProperties props;
    private final HttpJson http;

    public GeminiModelDiscovery(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.http = new HttpJson(mapper);
    }

    @Override
    public String provider() {
        return "gemini";
    }

    @Override
    public List<DiscoveredModel> discover() {
        Map<String, DiscoveredModel> models = new LinkedHashMap<>();
        // Curated, vetted catalog (latest models).
        add(models, "gemini-3.5-flash", new ModelCapabilities(CTX, true, true, true, 0.000075, 0.0003, "flagship"), true);
        add(models, "gemini-3.1-flash-lite", new ModelCapabilities(CTX, true, true, true, 0.00004, 0.00015, "lite"), true);
        add(models, "gemini-2.5-flash", new ModelCapabilities(CTX, true, true, true, 0.000075, 0.0003, "flash"), true);
        add(models, "gemini-2.0-flash", new ModelCapabilities(CTX, true, true, true, 0.00005, 0.0002, "flash"), true);

        // Best-effort live augmentation.
        String key = props.getGemini().getApiKey();
        if (key != null && !key.isBlank()) {
            try {
                JsonNode resp = http.post(props.getGemini().getBaseUrl() + "/v1beta/models?key=" + key,
                        Map.of(), new String[]{}, 10); // some deployments accept POST; tolerate failure
                for (JsonNode m : resp.path("models")) {
                    String name = m.path("name").asText("").replace("models/", "");
                    if (!name.isBlank() && !models.containsKey(name)) {
                        int ctx = m.path("inputTokenLimit").asInt(CTX);
                        add(models, name, new ModelCapabilities(ctx, true, true, true, 0.0001, 0.0004, "discovered"), false);
                    }
                }
            } catch (Exception e) {
                log.debug("Gemini live model discovery skipped: {}", e.getMessage());
            }
        }
        return new ArrayList<>(models.values());
    }

    private void add(Map<String, DiscoveredModel> map, String name, ModelCapabilities caps, boolean vetted) {
        map.put(name, new DiscoveredModel(provider(), name, caps, vetted));
    }
}
