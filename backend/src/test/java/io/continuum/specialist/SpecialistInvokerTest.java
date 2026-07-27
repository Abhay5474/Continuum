package io.continuum.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.continuum.net.GuardedHttpSender;
import io.continuum.persistence.entity.SpecialistConnectionEntity;
import io.continuum.persistence.entity.SpecialistEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the invoker against a real HTTP server, because the parts most likely
 * to be wrong are the ones between the adapter and the wire: how the credential
 * is presented, what happens on a 4xx, and whether a low-confidence finding is
 * genuinely withheld from the model rather than merely flagged.
 */
class SpecialistInvokerTest {

    private HttpServer server;
    private SpecialistInvoker invoker;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> lastHeaders = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String response = "{}";

    private SpecialistConnectionEntity connection;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().toString());
            lastHeaders.set(Map.copyOf(exchange.getRequestHeaders()));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        connection = new SpecialistConnectionEntity("dev-1", "test", "http",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                SpecialistConnectionEntity.AuthStyle.BEARER, null);
        connection.setCredentialRef("specialist:1");

        // Loopback is refused in production; the permissive flag is what makes a
        // test against a local server possible at all.
        invoker = new SpecialistInvoker(stubConnections(), new GuardedHttpSender(true),
                stubTraces(), new ObjectMapper());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SpecialistConnectionService stubConnections() {
        return new SpecialistConnectionService(null, null) {
            @Override
            public SpecialistConnectionEntity require(String developerId, Long id) {
                return connection;
            }

            @Override
            Optional<String> secretFor(SpecialistConnectionEntity c) {
                return Optional.of("sk-test-secret");
            }

            @Override
            public void recordSuccess(SpecialistConnectionEntity c) {
            }

            @Override
            public void recordFailure(SpecialistConnectionEntity c, String error) {
            }
        };
    }

    private TraceRecorder stubTraces() {
        return new TraceRecorder(null, new ObjectMapper()) {
            @Override
            public void step(String traceId, String developerId,
                             io.continuum.persistence.entity.TraceStepEntity.Kind kind, String label,
                             Object detail, String s, Double confidence, double cost, long ms) {
            }
        };
    }

    private SpecialistEntity specialist(double minConfidence) {
        return new SpecialistEntity("dev-1", 1L, "detector", "/predict", "image", minConfidence, 10);
    }

    @Test
    @DisplayName("findings are normalised and returned strongest first")
    void invokesAndNormalises() {
        response = "{\"predictions\":[{\"label\":\"bruise\",\"confidence\":0.55},"
                + "{\"label\":\"wound\",\"confidence\":0.91}]}";

        var r = invoker.invoke(specialist(0.3), Map.of("imageBase64", "AAAA"), null);

        assertThat(r.ok()).isTrue();
        assertThat(r.findings()).hasSize(2);
        assertThat(r.top().label()).isEqualTo("wound");
        assertThat(r.topConfidence()).isEqualTo(0.91);
    }

    @Test
    @DisplayName("a finding below the threshold never reaches the caller")
    void lowConfidenceIsWithheld() {
        response = "{\"predictions\":[{\"label\":\"wound\",\"confidence\":0.91},"
                + "{\"label\":\"fracture\",\"confidence\":0.12}]}";

        var r = invoker.invoke(specialist(0.5), Map.of("imageBase64", "AAAA"), null);

        // Passing it along with a caveat invites the model to reason about it
        // anyway, which is how a 0.12 detection becomes a paragraph of advice.
        assertThat(r.findings()).hasSize(1);
        assertThat(r.findings().get(0).label()).isEqualTo("wound");
        // But the count is reported: "nothing found" and "nothing confident
        // enough" are different situations.
        assertThat(r.dropped()).isEqualTo(1);
    }

    @Test
    @DisplayName("the bearer credential is presented, and never appears in the URL")
    void bearerCredentialIsSent() {
        response = "{\"predictions\":[]}";

        invoker.invoke(specialist(0.3), Map.of("imageBase64", "AAAA"), null);

        assertThat(lastHeaders.get().get("Authorization")).containsExactly("Bearer sk-test-secret");
        assertThat(lastPath.get()).doesNotContain("sk-test-secret");
    }

    @Test
    @DisplayName("a query-style credential goes in the URL, which is what Roboflow expects")
    void queryCredentialIsSent() {
        connection = new SpecialistConnectionEntity("dev-1", "test", "http",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                SpecialistConnectionEntity.AuthStyle.QUERY, "api_key");
        connection.setCredentialRef("specialist:1");
        response = "{\"predictions\":[]}";

        invoker.invoke(specialist(0.3), Map.of("imageBase64", "AAAA"), null);

        assertThat(lastPath.get()).contains("api_key=sk-test-secret");
        assertThat(lastHeaders.get().get("Authorization")).isNull();
    }

    @Test
    @DisplayName("a provider error is reported, not thrown")
    void providerErrorIsReported() {
        status = 403;
        response = "{\"error\":\"invalid api key\"}";

        var r = invoker.invoke(specialist(0.3), Map.of("imageBase64", "AAAA"), null);

        // The next decision — proceed without the specialist, or refuse —
        // belongs to the confidence policy, not to the transport.
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("403").contains("invalid api key");
        assertThat(r.findings()).isEmpty();
    }

    @Test
    @DisplayName("a non-JSON response yields no findings rather than an exception")
    void nonJsonResponseIsSurvivable() {
        response = "upstream timeout";

        var r = invoker.invoke(specialist(0.3), Map.of("imageBase64", "AAAA"), null);

        assertThat(r.ok()).isTrue();
        assertThat(r.findings()).isEmpty();
    }

    @Test
    @DisplayName("a refused target fails the invocation without throwing")
    void refusedTargetIsReported() {
        connection = new SpecialistConnectionEntity("dev-1", "test", "http",
                "file:///etc/passwd", SpecialistConnectionEntity.AuthStyle.NONE, null);
        SpecialistInvoker guarded = new SpecialistInvoker(stubConnections(),
                new GuardedHttpSender(false), stubTraces(), new ObjectMapper());

        var r = guarded.invoke(specialist(0.3), Map.of("imageBase64", "AAAA"), null);

        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("http or https");
    }

    @Test
    @DisplayName("the configured threshold is passed to the provider as well as applied here")
    void thresholdTravelsWithTheRequest() {
        response = "{\"predictions\":[]}";

        invoker.invoke(specialist(0.65), Map.of("imageBase64", "AAAA"), null);

        assertThat(lastBody.get()).contains("minConfidence");
    }
}
