package io.continuum.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.continuum.chaos.ChaosMonkey;
import io.continuum.config.LlmProperties;
import io.continuum.provider.gemini.GeminiProvider;
import io.continuum.provider.groq.GroqProvider;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import io.continuum.routing.ProviderMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Streaming: each provider hands text on as its server-sent events arrive, an
 * answer cut off mid-way is a failure rather than a short answer, and the
 * router fails over only while nothing has been sent.
 */
class ProviderStreamTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Serves the given SSE events, one flush each; records the request body and path. */
    private String sse(List<String> events, AtomicReference<String> body, AtomicReference<String> path) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            path.set(ex.getRequestURI().toString());
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                for (String e : events) {
                    out.write(("data: " + e + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static LlmRequest ask() {
        return new LlmRequest("m", List.of(Message.user("hi")), 100, 0.2);
    }

    private GroqProvider groq(String base) {
        LlmProperties props = new LlmProperties();
        props.getGroq().setApiKey("gsk_test");
        props.getGroq().setBaseUrl(base);
        return new GroqProvider(props, json);
    }

    private GeminiProvider gemini(String base) {
        LlmProperties props = new LlmProperties();
        props.getGemini().setApiKey("AIza_test");
        props.getGemini().setBaseUrl(base);
        return new GeminiProvider(props, json, new ChaosMonkey());
    }

    private static final String G1 = "{\"choices\":[{\"delta\":{\"content\":\"Hel\"},\"finish_reason\":null}]}";
    private static final String G2 = "{\"choices\":[{\"delta\":{\"content\":\"lo\"},\"finish_reason\":null}]}";
    private static final String GEND = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}";
    private static final String GUSAGE = "{\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}";

    @Test
    void groqHandsOnEachDeltaAndReturnsTheWholeAnswerWithItsUsage() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        String base = sse(List.of(G1, G2, GEND, GUSAGE, "[DONE]"), body, path);
        List<String> got = new ArrayList<>();

        LlmResponse r = groq(base).stream(ask(), null, got::add);

        assertThat(got).containsExactly("Hel", "lo");
        assertThat(r.content()).isEqualTo("Hello");
        assertThat(r.promptTokens()).isEqualTo(5);
        assertThat(r.completionTokens()).isEqualTo(2);
        assertThat(r.finishReason()).isEqualTo("stop");
        assertThat(json.readTree(body.get()).path("stream").asBoolean()).isTrue();
    }

    @Test
    void aGroqStreamThatStopsWithoutFinishingIsAFailure() throws Exception {
        String base = sse(List.of(G1, G2), new AtomicReference<>(), new AtomicReference<>());
        List<String> got = new ArrayList<>();

        assertThatThrownBy(() -> groq(base).stream(ask(), null, got::add))
                .hasMessageContaining("ended before the answer finished");
        assertThat(got).containsExactly("Hel", "lo");
    }

    @Test
    void geminiStreamsTextPartsSkippingThoughtsAndReadsUsageFromTheLastEvent() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        String base = sse(List.of(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"thinking\",\"thought\":true}]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Bon\"}]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"jour\"}]},\"finishReason\":\"STOP\"}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":4,\"candidatesTokenCount\":2}}"),
                new AtomicReference<>(), path);
        List<String> got = new ArrayList<>();

        LlmResponse r = gemini(base).stream(ask(), null, got::add);

        assertThat(got).containsExactly("Bon", "jour");
        assertThat(r.content()).isEqualTo("Bonjour");
        assertThat(r.promptTokens()).isEqualTo(4);
        assertThat(path.get()).contains(":streamGenerateContent").contains("alt=sse");
    }

    @Test
    void aGeminiStreamWithoutAFinishReasonIsAFailure() throws Exception {
        String base = sse(List.of("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Bon\"}]}}]}"),
                new AtomicReference<>(), new AtomicReference<>());

        assertThatThrownBy(() -> gemini(base).stream(ask(), null, d -> { }))
                .hasMessageContaining("ended before the answer finished");
    }

    /** A provider that streams a few pieces, optionally failing before or after the first. */
    static final class Scripted implements LlmProvider {
        final String name;
        final boolean failBefore;
        final boolean failAfter;

        Scripted(String name, boolean failBefore, boolean failAfter) {
            this.name = name;
            this.failBefore = failBefore;
            this.failAfter = failAfter;
        }

        @Override public String name() { return name; }
        @Override public boolean isAvailable() { return true; }
        @Override public double estimateCost(String m, int p, int c) { return 0; }
        @Override public LlmResponse complete(LlmRequest r) { return new LlmResponse(name, List.of(), 1, 1, name, "m", "stop"); }

        @Override
        public LlmResponse stream(LlmRequest r, String key, TokenSink sink) throws Exception {
            if (failBefore) {
                throw new java.io.IOException(name + " refused");
            }
            sink.accept(name + "-1 ");
            if (failAfter) {
                throw new java.io.IOException(name + " dropped");
            }
            sink.accept(name + "-2");
            return new LlmResponse(name + "-1 " + name + "-2", List.of(), 1, 2, name, "m", "stop");
        }
    }

    @SuppressWarnings("unchecked")
    private static ProviderRouter router(LlmProvider... providers) {
        LlmProperties props = new LlmProperties();
        List<String> order = new ArrayList<>();
        for (LlmProvider p : providers) {
            order.add(p.name());
        }
        props.setFailoverOrder(order);
        return new ProviderRouter(List.of(providers), props, mock(ObjectProvider.class));
    }

    @Test
    void theRouterFailsOverWhileNothingHasBeenSent() {
        List<String> got = new ArrayList<>();
        LlmResponse r = router(new Scripted("a", true, false), new Scripted("b", false, false))
                .stream(ask(), List.of("a", "b"), null, got::add);

        assertThat(r.provider()).isEqualTo("b");
        assertThat(got).containsExactly("b-1 ", "b-2");
    }

    @Test
    void onceTextHasBeenSentAFailureEndsTheRequestInsteadOfAppendingAnotherProvidersAnswer() {
        List<String> got = new ArrayList<>();
        ProviderRouter router = router(new Scripted("a", false, true), new Scripted("b", false, false));

        assertThatThrownBy(() -> router.stream(ask(), List.of("a", "b"), null, got::add))
                .isInstanceOf(ProviderRouter.StreamBrokenException.class)
                .hasMessageContaining("stopped partway");
        assertThat(got).containsExactly("a-1 ");
    }

    @Test
    void aProviderThatCannotStreamHandsOverItsWholeAnswerOnce() throws Exception {
        List<String> got = new ArrayList<>();
        LlmProvider plain = new LlmProvider() {
            @Override public String name() { return "plain"; }
            @Override public boolean isAvailable() { return true; }
            @Override public double estimateCost(String m, int p, int c) { return 0; }
            @Override public LlmResponse complete(LlmRequest r) { return new LlmResponse("whole", List.of(), 1, 1, "plain", "m", "stop"); }
        };

        LlmResponse r = plain.stream(ask(), null, got::add);

        assertThat(got).containsExactly("whole");
        assertThat(r.content()).isEqualTo("whole");
    }
}
