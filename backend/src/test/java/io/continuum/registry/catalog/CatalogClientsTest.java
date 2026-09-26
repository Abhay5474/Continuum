package io.continuum.registry.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.continuum.config.LlmProperties;
import io.continuum.provider.HttpJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two provider clients against a local server that answers the way Groq and
 * Gemini do — including the answers the old discovery never saw, because it
 * sent the list request as a POST.
 */
class CatalogClientsTest {

    private HttpServer server;
    private String base;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile Map<String, String> headers = Map.of();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String goog = ex.getRequestHeaders().getFirst("x-goog-api-key");
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + auth + " goog=" + goog);
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GroqCatalogClient groq() {
        LlmProperties p = new LlmProperties();
        p.getGroq().setApiKey("gsk_test");
        p.getGroq().setBaseUrl(base + "/openai/v1");
        return new GroqCatalogClient(p, new ObjectMapper());
    }

    private GeminiCatalogClient gemini() {
        LlmProperties p = new LlmProperties();
        p.getGemini().setApiKey("AIza_test");
        p.getGemini().setBaseUrl(base);
        return new GeminiCatalogClient(p, new ObjectMapper());
    }

    @Test
    void groqListIsAGetWithTheKeyInTheHeaderAndClassifiesEveryModel() {
        body = """
                {"object":"list","data":[
                 {"id":"openai/gpt-oss-120b","object":"model","created":1754408224,"owned_by":"OpenAI","active":true,"context_window":131072,"max_completion_tokens":65536},
                 {"id":"whisper-large-v3","object":"model","owned_by":"OpenAI","active":true,"context_window":448},
                 {"id":"meta-llama/llama-guard-4-12b","active":true,"context_window":131072},
                 {"id":"playai-tts","active":true},
                 {"id":"groq/compound","active":true,"context_window":131072},
                 {"id":"llama-3.3-70b-versatile","active":false,"context_window":131072}
                ]}""";

        ListResult r = groq().list();

        assertThat(seen).singleElement().asString()
                .startsWith("GET /openai/v1/models").contains("auth=Bearer gsk_test");
        assertThat(r.ok()).isTrue();
        assertThat(r.models()).extracting(ListedModel::id)
                .containsExactly("openai/gpt-oss-120b", "whisper-large-v3", "meta-llama/llama-guard-4-12b",
                        "playai-tts", "groq/compound");
        assertThat(r.models()).extracting(ListedModel::kind).containsExactly(ModelKind.CHAT,
                ModelKind.SPEECH_TO_TEXT, ModelKind.SAFETY, ModelKind.TEXT_TO_SPEECH, ModelKind.OTHER);
        assertThat(r.models().get(0).contextWindow()).isEqualTo(131072);
        assertThat(r.models().get(0).maxOutputTokens()).isEqualTo(65536);
        assertThat(r.models().get(0).createdEpoch()).isEqualTo(1754408224L);
    }

    @Test
    void aFailedOrRefusedListIsNeverAnEmptyCatalogue() {
        status = 503;
        assertThat(groq().list().ok()).isFalse();
        status = 401;
        ListResult refused = groq().list();
        assertThat(refused.ok()).isFalse();
        assertThat(refused.keyRejected()).isTrue();
        status = 200;
        body = "not json";
        assertThat(groq().list().ok()).isFalse();
    }

    @Test
    void groqProbeReadsTheFreeLimitsFromTheResponseHeaders() {
        body = "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}";
        headers = Map.of("x-ratelimit-limit-requests", "1000", "x-ratelimit-limit-tokens", "8000");

        ProbeResult r = groq().probe("openai/gpt-oss-120b");

        assertThat(r.outcome()).isEqualTo(ProbeResult.Outcome.CALLABLE);
        assertThat(r.limits()).containsEntry("requestsPerDay", 1000L).containsEntry("tokensPerMinute", 8000L);
        assertThat(seen.get(0)).startsWith("POST /openai/v1/chat/completions");
    }

    @Test
    void groqProbeOutcomes() {
        assertThat(GroqCatalogClient.classify(res(429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}")).outcome())
                .isEqualTo(ProbeResult.Outcome.RATE_LIMITED);
        assertThat(GroqCatalogClient.classify(res(404, "{\"error\":{\"code\":\"model_not_found\"}}")).outcome())
                .isEqualTo(ProbeResult.Outcome.NO_ACCESS);
        assertThat(GroqCatalogClient.classify(res(400, "{\"error\":{\"code\":\"model_terms_required\"}}")).outcome())
                .isEqualTo(ProbeResult.Outcome.NO_ACCESS);
        assertThat(GroqCatalogClient.classify(res(401, "{}")).outcome()).isEqualTo(ProbeResult.Outcome.KEY_REJECTED);
        assertThat(GroqCatalogClient.classify(res(502, "")).outcome()).isEqualTo(ProbeResult.Outcome.TRANSIENT);
        assertThat(GroqCatalogClient.classify(res(400, "{\"error\":{\"message\":\"bad\"}}")).outcome())
                .isEqualTo(ProbeResult.Outcome.REJECTED);
    }

    @Test
    void groqSaysGoneInTwoWays() {
        assertThat(GroqCatalogClient.gone(404, "{\"error\":{\"message\":\"The model `llama-3.3-70b-versatile` does not "
                + "exist or you do not have access to it.\",\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}")).isTrue();
        assertThat(GroqCatalogClient.gone(400, "{\"error\":{\"code\":\"model_decommissioned\"}}")).isTrue();
        assertThat(GroqCatalogClient.gone(429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}")).isFalse();
        assertThat(GroqCatalogClient.gone(404, "Not Found")).isFalse();
    }

    @Test
    void geminiListFollowsPagesAndKeepsTheKeyOutOfTheUrl() {
        body = """
                {"models":[
                 {"name":"models/gemini-3.5-flash","displayName":"Gemini 3.5 Flash","inputTokenLimit":1048576,"outputTokenLimit":65536,"supportedGenerationMethods":["generateContent","countTokens"]},
                 {"name":"models/gemini-3.5-flash-001","supportedGenerationMethods":["generateContent"]},
                 {"name":"models/gemini-flash-latest","supportedGenerationMethods":["generateContent"]},
                 {"name":"models/gemini-3.8-flash-preview","supportedGenerationMethods":["generateContent"]},
                 {"name":"models/gemini-embedding-001","supportedGenerationMethods":["embedContent"]},
                 {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
                 {"name":"models/imagen-4.0-generate-001","supportedGenerationMethods":["predict"]},
                 {"name":"models/gemma-3-27b-it","supportedGenerationMethods":["generateContent"]}
                ]}""";

        ListResult r = gemini().list();

        assertThat(seen.get(0)).startsWith("GET /v1beta/models?pageSize=1000").contains("goog=AIza_test")
                .doesNotContain("key=");
        assertThat(r.models()).extracting(ListedModel::kind).containsExactly(ModelKind.CHAT, ModelKind.ALIAS,
                ModelKind.ALIAS, ModelKind.CHAT, ModelKind.EMBEDDING, ModelKind.TEXT_TO_SPEECH, ModelKind.IMAGE,
                ModelKind.OTHER);
        assertThat(r.models().get(0).displayName()).isEqualTo("Gemini 3.5 Flash");
        assertThat(r.models().get(0).contextWindow()).isEqualTo(1048576);
        assertThat(r.models().get(3).preview()).isTrue();
        assertThat(r.models().get(1).note()).isEqualTo("Pinned version of gemini-3.5-flash");
    }

    @Test
    void geminiWithoutFreeQuotaIsNotFreeButABusyOneIsOnlyBusy() {
        String zero = "{\"error\":{\"code\":429,\"message\":\"You exceeded your current quota. Quota exceeded for "
                + "metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 0, "
                + "model: gemini-3.8-flash\",\"status\":\"RESOURCE_EXHAUSTED\"}}";
        String busy = "{\"error\":{\"code\":429,\"message\":\"Quota exceeded for metric: "
                + "generate_content_free_tier_requests, limit: 250\",\"status\":\"RESOURCE_EXHAUSTED\"}}";
        assertThat(GeminiCatalogClient.classify(res(429, zero)).outcome()).isEqualTo(ProbeResult.Outcome.NOT_FREE);
        assertThat(GeminiCatalogClient.classify(res(429, busy)).outcome()).isEqualTo(ProbeResult.Outcome.RATE_LIMITED);
        assertThat(GeminiCatalogClient.noFreeQuota(429, zero)).isTrue();
        assertThat(GeminiCatalogClient.noFreeQuota(429, busy)).isFalse();
        assertThat(GeminiCatalogClient.classify(res(404, "{}")).outcome()).isEqualTo(ProbeResult.Outcome.NO_ACCESS);
        assertThat(GeminiCatalogClient.classify(res(400, "{\"error\":{\"message\":\"User location is not supported\"}}"))
                .outcome()).isEqualTo(ProbeResult.Outcome.NO_ACCESS);
        assertThat(GeminiCatalogClient.classify(res(400, "API_KEY_INVALID")).outcome())
                .isEqualTo(ProbeResult.Outcome.KEY_REJECTED);
    }

    @Test
    void geminiSaysGoneWithA404NamingTheModel() {
        assertThat(GeminiCatalogClient.gone(404, "{\"error\":{\"code\":404,\"message\":\"models/gemini-2.5-flash is not "
                + "found for API version v1beta, or is not supported for generateContent.\",\"status\":\"NOT_FOUND\"}}")).isTrue();
        assertThat(GeminiCatalogClient.gone(404, "<html>Not Found</html>")).isFalse();
        assertThat(GeminiCatalogClient.gone(500, "models/x not found")).isFalse();
    }

    private static HttpJson.Result res(int status, String body) {
        return new HttpJson.Result(status, Map.of(), body);
    }
}
