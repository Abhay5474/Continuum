package io.continuum.registry.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.config.LlmProperties;
import io.continuum.provider.HttpJson;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Groq's catalogue, from {@code GET /openai/v1/models}.
 *
 * <p>Each entry carries {@code id}, {@code active}, {@code context_window},
 * {@code max_completion_tokens}, {@code created} and {@code owned_by}. An entry
 * with {@code active: false} is treated as not listed. Groq lists speech,
 * text-to-speech and guard models beside the chat ones; those are catalogued but
 * never tested or routed.
 *
 * <p>Probe answers carry Groq's rate-limit headers, which are the per-model free
 * limits for this key — read from the provider rather than copied from a page.
 */
@Component
public class GroqCatalogClient implements ProviderCatalogClient {

    private final LlmProperties.Provider config;
    private final HttpJson http;
    private final ObjectMapper mapper;

    public GroqCatalogClient(LlmProperties props, ObjectMapper mapper) {
        this.config = props.getGroq();
        this.http = new HttpJson(mapper);
        this.mapper = mapper;
    }

    @Override
    public String provider() {
        return "groq";
    }

    @Override
    public boolean configured() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    @Override
    public boolean freeTier() {
        return config.isFreeTier();
    }

    @Override
    public ListResult list() {
        try {
            HttpJson.Result r = http.get(config.getBaseUrl() + "/models",
                    new String[]{"Authorization", "Bearer " + config.getApiKey()}, 15);
            if (r.status() == 401 || r.status() == 403) {
                return ListResult.keyRejected("Groq refused the API key (HTTP " + r.status() + ")");
            }
            if (!r.ok()) {
                return ListResult.failed("Groq model list answered HTTP " + r.status());
            }
            JsonNode data = mapper.readTree(r.body()).path("data");
            if (!data.isArray()) {
                return ListResult.failed("Groq model list had no data array");
            }
            List<ListedModel> out = new ArrayList<>();
            for (JsonNode m : data) {
                String id = m.path("id").asText("");
                if (id.isBlank() || (m.has("active") && !m.path("active").asBoolean(true))) {
                    continue;
                }
                ModelKind kind = kindOf(id);
                out.add(new ListedModel(id, kind, null, m.path("owned_by").asText(null),
                        m.path("context_window").asInt(0), m.path("max_completion_tokens").asInt(0),
                        m.path("created").asLong(0), id.toLowerCase(Locale.ROOT).contains("preview"),
                        noteFor(kind)));
            }
            return ListResult.ok(out);
        } catch (Exception e) {
            return ListResult.failed("Groq model list could not be read: " + e.getClass().getSimpleName());
        }
    }

    /** Read from the id: Groq's list does not say what a model is for. */
    static ModelKind kindOf(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        if (s.contains("whisper")) return ModelKind.SPEECH_TO_TEXT;
        if (s.contains("tts") || s.startsWith("playai") || s.contains("orpheus")) return ModelKind.TEXT_TO_SPEECH;
        if (s.contains("guard")) return ModelKind.SAFETY;
        if (s.contains("embed")) return ModelKind.EMBEDDING;
        if (s.contains("compound")) return ModelKind.OTHER;
        return ModelKind.CHAT;
    }

    private static String noteFor(ModelKind kind) {
        return switch (kind) {
            case SPEECH_TO_TEXT -> "Speech-to-text model — not used for chat";
            case TEXT_TO_SPEECH -> "Text-to-speech model — not used for chat";
            case SAFETY -> "Safety classifier — not used for chat";
            case EMBEDDING -> "Embedding model — not used for chat";
            case OTHER -> "Agentic system with its own built-in tools — not routed as a plain chat model";
            default -> null;
        };
    }

    @Override
    public ProbeResult probe(String modelId) {
        Map<String, Object> body = Map.of(
                "model", modelId,
                "messages", List.of(Map.of("role", "user", "content", "Reply with OK.")),
                // Enough for a reasoning model to answer at all; small enough to cost nothing.
                "max_tokens", 16);
        try {
            HttpJson.Result r = http.exchange(config.getBaseUrl() + "/chat/completions", body,
                    new String[]{"Authorization", "Bearer " + config.getApiKey()}, 25);
            return classify(r);
        } catch (Exception e) {
            return ProbeResult.of(ProbeResult.Outcome.TRANSIENT, "No answer: " + e.getClass().getSimpleName());
        }
    }

    static ProbeResult classify(HttpJson.Result r) {
        String body = r.body() == null ? "" : r.body();
        if (r.ok()) {
            return new ProbeResult(ProbeResult.Outcome.CALLABLE, "Answered a test request", limitsFrom(r));
        }
        int s = r.status();
        if (s == 401) {
            return ProbeResult.of(ProbeResult.Outcome.KEY_REJECTED, "Groq refused the API key");
        }
        if (s == 429) {
            return ProbeResult.of(ProbeResult.Outcome.RATE_LIMITED, "Rate-limited just now");
        }
        if (s >= 500) {
            return ProbeResult.of(ProbeResult.Outcome.TRANSIENT, "Groq answered HTTP " + s);
        }
        if (body.contains("model_terms_required") || body.toLowerCase(Locale.ROOT).contains("accept the terms")) {
            return ProbeResult.of(ProbeResult.Outcome.NO_ACCESS, "Needs its terms accepted in the Groq console first");
        }
        if (s == 403) {
            return ProbeResult.of(ProbeResult.Outcome.NO_ACCESS, "Blocked for this key or organisation");
        }
        if (s == 404 || body.contains("model_not_found") || body.contains("model_decommissioned")) {
            return ProbeResult.of(ProbeResult.Outcome.NO_ACCESS, "Listed, but not callable with this key");
        }
        return ProbeResult.of(ProbeResult.Outcome.REJECTED, "Refused a minimal chat request (HTTP " + s + ")");
    }

    /**
     * Groq's limits for this key and model, from its response headers: requests
     * per day and tokens per minute.
     */
    static Map<String, Object> limitsFrom(HttpJson.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "requestsPerDay", r.header("x-ratelimit-limit-requests"));
        put(out, "tokensPerMinute", r.header("x-ratelimit-limit-tokens"));
        if (!out.isEmpty()) {
            out.put("source", "Groq response headers");
        }
        return out;
    }

    private static void put(Map<String, Object> out, String key, String value) {
        if (value == null) {
            return;
        }
        try {
            out.put(key, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            // A value we cannot read is left out rather than shown wrong.
        }
    }

    @Override
    public List<ListedModel> seeds() {
        // Groq's own recommended replacements for the Llama models it retired on
        // 16 Aug 2026. Only used until the first successful list, which is the
        // authority from then on.
        return List.of(
                new ListedModel("openai/gpt-oss-120b", ModelKind.CHAT, null, "openai", 131_072, 0, 0, false, null),
                new ListedModel("openai/gpt-oss-20b", ModelKind.CHAT, null, "openai", 131_072, 0, 0, false, null));
    }

    @Override
    public Map<String, String> retiredBeforeCatalogue() {
        String why = "Retired by Groq on 16 Aug 2026 for free and developer accounts";
        return Map.of("llama-3.3-70b-versatile", why, "llama-3.1-8b-instant", why);
    }

    @Override
    public boolean isModelGone(int status, String body) {
        return gone(status, body);
    }

    /**
     * Groq's two ways of saying a model is gone: {@code 400 model_decommissioned},
     * and {@code 404 model_not_found} ("does not exist or you do not have
     * access to it"). The second is ambiguous, which is why a report is
     * confirmed against the model list before anything is retired.
     */
    public static boolean gone(int status, String body) {
        String b = body == null ? "" : body;
        return b.contains("model_decommissioned") || (status == 404 && b.contains("model_not_found"));
    }
}
