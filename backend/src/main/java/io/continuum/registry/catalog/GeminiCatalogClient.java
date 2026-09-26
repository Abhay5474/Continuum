package io.continuum.registry.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.config.LlmProperties;
import io.continuum.provider.HttpJson;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini's catalogue, from {@code GET /v1beta/models}.
 *
 * <p>Each entry carries {@code name}, {@code displayName}, {@code description},
 * {@code inputTokenLimit}, {@code outputTokenLimit} and the
 * {@code supportedGenerationMethods} that say what it can do. The key goes in
 * the {@code x-goog-api-key} header: on the query string it was part of the URL,
 * and URLs end up in error messages and logs.
 *
 * <p>Whether a model is on the free tier is not in the list. The probe finds
 * out: on a key without billing, a model outside the free tier answers 429 with
 * a quota limit of zero.
 */
@Component
public class GeminiCatalogClient implements ProviderCatalogClient {

    /** A version-pinned id such as {@code gemini-2.0-flash-001}. */
    private static final Pattern PINNED = Pattern.compile("^(.+)-(\\d{3})$");
    /** Google's way of saying a model has no free quota for this project. */
    private static final Pattern ZERO_QUOTA = Pattern.compile("limit:\\s*0\\b|\"quotaValue\"\\s*:\\s*\"0\"");
    private static final int MAX_PAGES = 3;

    private final LlmProperties.Provider config;
    private final HttpJson http;
    private final ObjectMapper mapper;

    public GeminiCatalogClient(LlmProperties props, ObjectMapper mapper) {
        this.config = props.getGemini();
        this.http = new HttpJson(mapper);
        this.mapper = mapper;
    }

    @Override
    public String provider() {
        return "gemini";
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
        List<JsonNode> raw = new ArrayList<>();
        String pageToken = null;
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                String url = config.getBaseUrl() + "/v1beta/models?pageSize=1000"
                        + (pageToken == null ? "" : "&pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8));
                HttpJson.Result r = http.get(url, new String[]{"x-goog-api-key", config.getApiKey()}, 15);
                if (r.status() == 401 || r.status() == 403 || (r.status() == 400 && r.body() != null
                        && r.body().contains("API_KEY_INVALID"))) {
                    return ListResult.keyRejected("Google refused the API key (HTTP " + r.status() + ")");
                }
                if (!r.ok()) {
                    return ListResult.failed("Gemini model list answered HTTP " + r.status());
                }
                JsonNode root = mapper.readTree(r.body());
                if (!root.path("models").isArray()) {
                    return ListResult.failed("Gemini model list had no models array");
                }
                root.path("models").forEach(raw::add);
                pageToken = root.path("nextPageToken").asText(null);
                if (pageToken == null || pageToken.isBlank()) {
                    break;
                }
            }
        } catch (Exception e) {
            return ListResult.failed("Gemini model list could not be read: " + e.getClass().getSimpleName());
        }

        Set<String> names = new HashSet<>();
        for (JsonNode m : raw) {
            names.add(idOf(m));
        }
        List<ListedModel> out = new ArrayList<>();
        for (JsonNode m : raw) {
            String id = idOf(m);
            if (id.isBlank()) {
                continue;
            }
            List<String> methods = new ArrayList<>();
            m.path("supportedGenerationMethods").forEach(x -> methods.add(x.asText()));
            ModelKind kind = kindOf(id, methods);
            String note = noteFor(kind, id);
            Matcher pinned = PINNED.matcher(id);
            if (kind == ModelKind.CHAT && pinned.matches() && names.contains(pinned.group(1))) {
                kind = ModelKind.ALIAS;
                note = "Pinned version of " + pinned.group(1);
            } else if (kind == ModelKind.CHAT && id.endsWith("-latest")) {
                kind = ModelKind.ALIAS;
                note = "Moving alias — Google points it at whichever model is current";
            }
            String lower = id.toLowerCase(Locale.ROOT);
            out.add(new ListedModel(id, kind, m.path("displayName").asText(null),
                    m.path("description").asText(null),
                    m.path("inputTokenLimit").asInt(0), m.path("outputTokenLimit").asInt(0), 0,
                    lower.contains("preview") || lower.contains("-exp") || lower.contains("experimental"),
                    note));
        }
        return ListResult.ok(out);
    }

    private static String idOf(JsonNode m) {
        String name = m.path("name").asText("");
        return name.startsWith("models/") ? name.substring("models/".length()) : name;
    }

    static ModelKind kindOf(String id, List<String> methods) {
        String s = id.toLowerCase(Locale.ROOT);
        if (!methods.contains("generateContent")) {
            if (methods.contains("embedContent") || methods.contains("batchEmbedContents")) return ModelKind.EMBEDDING;
            if (methods.contains("predict") || methods.contains("predictLongRunning")) return ModelKind.IMAGE;
            if (methods.contains("bidiGenerateContent")) return ModelKind.AUDIO;
            return ModelKind.OTHER;
        }
        if (s.contains("embedding")) return ModelKind.EMBEDDING;
        if (s.contains("tts")) return ModelKind.TEXT_TO_SPEECH;
        if (s.contains("image") || s.contains("imagen")) return ModelKind.IMAGE;
        if (s.contains("native-audio") || s.contains("-live") || s.contains("audio")) return ModelKind.AUDIO;
        if (s.startsWith("gemma") || s.startsWith("aqa") || s.contains("robotics")
                || s.contains("computer-use") || s.startsWith("learnlm")) return ModelKind.OTHER;
        return ModelKind.CHAT;
    }

    private static String noteFor(ModelKind kind, String id) {
        return switch (kind) {
            case EMBEDDING -> "Embedding model — not used for chat";
            case TEXT_TO_SPEECH -> "Text-to-speech model — not used for chat";
            case IMAGE -> "Image model — not used for chat";
            case AUDIO -> "Live/audio model — not used for text chat";
            case OTHER -> id.toLowerCase(Locale.ROOT).startsWith("gemma")
                    ? "Open model without system-instruction support — not routed"
                    : "Specialised model — not routed as a general chat model";
            default -> null;
        };
    }

    @Override
    public ProbeResult probe(String modelId) {
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", "Reply with OK.")))),
                "generationConfig", Map.of("maxOutputTokens", 16));
        try {
            HttpJson.Result r = http.exchange(config.getBaseUrl() + "/v1beta/models/" + modelId + ":generateContent",
                    body, new String[]{"x-goog-api-key", config.getApiKey()}, 25);
            return classify(r);
        } catch (Exception e) {
            return ProbeResult.of(ProbeResult.Outcome.TRANSIENT, "No answer: " + e.getClass().getSimpleName());
        }
    }

    static ProbeResult classify(HttpJson.Result r) {
        String body = r.body() == null ? "" : r.body();
        if (r.ok()) {
            return ProbeResult.of(ProbeResult.Outcome.CALLABLE, "Answered a test request");
        }
        int s = r.status();
        if (s == 429) {
            return ZERO_QUOTA.matcher(body).find()
                    ? ProbeResult.of(ProbeResult.Outcome.NOT_FREE, "No free-tier quota for this model on this key")
                    : ProbeResult.of(ProbeResult.Outcome.RATE_LIMITED, "Rate-limited just now");
        }
        if (s == 401 || (s == 400 && body.contains("API_KEY_INVALID"))) {
            return ProbeResult.of(ProbeResult.Outcome.KEY_REJECTED, "Google refused the API key");
        }
        if (s >= 500) {
            return ProbeResult.of(ProbeResult.Outcome.TRANSIENT, "Gemini answered HTTP " + s);
        }
        if (body.contains("location is not supported")) {
            return ProbeResult.of(ProbeResult.Outcome.NO_ACCESS, "Not offered in this deployment's region");
        }
        if (s == 403 || s == 404) {
            return ProbeResult.of(ProbeResult.Outcome.NO_ACCESS, "Listed, but not callable with this key");
        }
        return ProbeResult.of(ProbeResult.Outcome.REJECTED, "Refused a minimal chat request (HTTP " + s + ")");
    }

    @Override
    public List<ListedModel> seeds() {
        // The model this deployment was running on when the catalogue was built.
        // Only used until the first successful list.
        return List.of(
                new ListedModel("gemini-3.5-flash", ModelKind.CHAT, "Gemini 3.5 Flash", null, 1_048_576, 0, 0, false, null));
    }

    @Override
    public boolean isModelGone(int status, String body) {
        return gone(status, body);
    }

    /** Gemini says a model is gone with a 404 naming it: "models/x is not found for API version v1beta". */
    public static boolean gone(int status, String body) {
        String b = body == null ? "" : body;
        return status == 404 && b.contains("models/") && (b.contains("not found") || b.contains("NOT_FOUND"));
    }

    /** A 429 whose quota limit is zero: this model has no free tier for this key. */
    public static boolean noFreeQuota(int status, String body) {
        return status == 429 && body != null && ZERO_QUOTA.matcher(body).find();
    }
}
