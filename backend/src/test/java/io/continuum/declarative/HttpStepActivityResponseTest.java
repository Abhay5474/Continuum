package io.continuum.declarative;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a step classifies a response, checked against a real server.
 *
 * <p>The distinction matters more than it looks: a 5xx is a blip worth retrying,
 * while a 4xx is a decision the target has already made. Retrying a declined
 * card twenty times helps nobody and delays the run's failure, so the two paths
 * are asserted separately rather than assumed.
 */
class HttpStepActivityResponseTest {

    private HttpServer server;
    private HttpStepActivity activity;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> lastIdempotencyKey = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            lastIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            String path = exchange.getRequestURI().getPath();
            int code = switch (path) {
                case "/boom" -> 503;
                case "/declined" -> 422;
                default -> 200;
            };
            byte[] body = "{\"holdId\":\"HOLD-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        // The target is loopback, which production rejects; the flag exists so a
        // self-hosted deployment can reach its own network, and it is what makes
        // this test possible.
        activity = new HttpStepActivity(new ObjectMapper(),
                new io.continuum.net.GuardedHttpSender(true));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private String input(String path) throws Exception {
        return new ObjectMapper().writeValueAsString(
                new HttpStepActivity.Input(url(path), "POST", Map.of(), Map.of("sku", "W-1"), 10, "wf-1:reserve"));
    }

    @Test
    @DisplayName("a 2xx returns the parsed body under 'body' so later steps can reference fields")
    void successExposesBody() throws Exception {
        Object out = activity.execute(input("/reserve"), null);

        assertThat(out).isInstanceOf(Map.class);
        Map<?, ?> m = (Map<?, ?>) out;
        assertThat(m.get("status")).isEqualTo(200);
        assertThat(m.get("body")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) m.get("body")).get("holdId")).isEqualTo("HOLD-1");
    }

    @Test
    @DisplayName("a 5xx throws a retryable failure")
    void serverErrorIsRetryable() {
        assertThatThrownBy(() -> activity.execute(input("/boom"), null))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(HttpStepActivity.NonRetryable.class)
                .hasMessageContaining("503");
    }

    @Test
    @DisplayName("a 4xx settles the step instead of consuming the retry budget")
    void clientErrorIsNotRetryable() {
        assertThatThrownBy(() -> activity.execute(input("/declined"), null))
                .isInstanceOf(HttpStepActivity.NonRetryable.class)
                .hasMessageContaining("422");
    }

    @Test
    @DisplayName("the step signs every call with its idempotency key so the target can dedupe")
    void sendsIdempotencyKey() throws Exception {
        activity.execute(input("/reserve"), null);

        assertThat(lastIdempotencyKey.get()).isEqualTo("wf-1:reserve");
        assertThat(hits.get()).isEqualTo(1);
    }
}
