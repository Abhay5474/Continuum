package io.continuum.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.declarative.DefinitionService;
import io.continuum.developer.DeveloperService;
import io.continuum.loops.LoopGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The program API: what an application holding only an API key can do beyond
 * chat — run a published workflow and follow it, use long-term memory, ask the
 * loop detector, rate an answer — over real HTTP, as an application would.
 */
class ProgramApiIT extends PostgresIT {

    @LocalServerPort int port;
    @Autowired io.continuum.portal.PortalService portal;
    @Autowired DeveloperService developers;
    @Autowired DefinitionService definitions;
    @Autowired LoopGuardService loops;
    @Autowired ObjectMapper json;
    @Autowired io.continuum.core.engine.WorkflowWorker workflowWorker;
    @Autowired io.continuum.core.engine.ActivityWorker activityWorker;

    private final HttpClient http = HttpClient.newHttpClient();
    private String dev;
    private String key;

    @BeforeEach
    void account() {
        dev = portal.signup("Program IT", "prog-" + UUID.randomUUID() + "@example.com", "correct-horse-9").developerId();
        key = developers.issueKey(dev, "program-it").plaintextKey();
    }

    private HttpResponse<String> call(String method, String path, Object body, String withKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (withKey != null) {
            b.header("Authorization", "Bearer " + withKey);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(HttpResponse<String> r) throws Exception {
        return json.readValue(r.body(), Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anApplicationRunsAPublishedWorkflowAndFollowsItsStepsWithItsKey() throws Exception {
        // Each step calls a public endpoint of this same server, so the run
        // needs nothing outside the test.
        definitions.publish(dev, "fetch-twice", Map.of("steps", List.of(
                Map.of("id", "first", "call", Map.of("method", "GET", "url", "http://localhost:" + port + "/api/meta")),
                Map.of("id", "second", "dependsOn", List.of("first"),
                        "call", Map.of("method", "GET", "url", "http://localhost:" + port + "/api/meta")))));

        HttpResponse<String> started = call("POST", "/api/gateway/workflows/fetch-twice/run",
                Map.of("input", Map.of("orderId", "BC-1")), key);
        assertThat(started.statusCode()).isEqualTo(200);
        String id = (String) read(started).get("workflowId");

        Map<String, Object> status = Map.of();
        for (int i = 0; i < 100; i++) {
            // Scheduling is off in tests; the pollers run here, as the scheduler would.
            workflowWorker.poll();
            activityWorker.poll();
            status = read(call("GET", "/api/gateway/workflows/" + id, null, key));
            if ("COMPLETED".equals(status.get("status")) || "FAILED".equals(status.get("status"))) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(status.get("status")).isEqualTo("COMPLETED");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) status.get("steps");
        assertThat(steps).extracting(s -> s.get("step")).containsExactly("first", "second");
        assertThat(steps).allSatisfy(s -> assertThat(s.get("state")).isEqualTo("completed"));
    }

    @Test
    void theCompletionCallbackCarriesTheBodyTheAuthorWrote() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> received = new java.util.concurrent.atomic.AtomicReference<>();
        com.sun.net.httpserver.HttpServer receiver = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/done", ex -> {
            received.set(new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        receiver.start();
        try {
            definitions.publish(dev, "with-callback", Map.of(
                    "steps", List.of(Map.of("id", "only",
                            "call", Map.of("method", "GET", "url", "http://localhost:" + port + "/api/meta"))),
                    "onComplete", Map.of("method", "POST",
                            "url", "http://127.0.0.1:" + receiver.getAddress().getPort() + "/done",
                            "body", Map.of("orderId", "${input.orderId}", "note", "refund for ${input.orderId}"))));
            String id = (String) read(call("POST", "/api/gateway/workflows/with-callback/run",
                    Map.of("input", Map.of("orderId", "BC-7")), key)).get("workflowId");
            for (int i = 0; i < 100 && received.get() == null; i++) {
                workflowWorker.poll();
                activityWorker.poll();
                Thread.sleep(100);
            }
            Map<?, ?> body = json.readValue(received.get(), Map.class);
            assertThat(body.get("orderId")).isEqualTo("BC-7");
            assertThat(body.get("note")).isEqualTo("refund for BC-7");
            assertThat(body.get("workflowId")).isEqualTo(id);
            assertThat(body.get("status")).isEqualTo("COMPLETED");
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void anotherAccountsKeyCannotSeeOrStopTheRun() throws Exception {
        definitions.publish(dev, "one-step", Map.of("steps", List.of(
                Map.of("id", "only", "call", Map.of("method", "GET", "url", "http://localhost:" + port + "/api/meta")))));
        String id = (String) read(call("POST", "/api/gateway/workflows/one-step/run", Map.of(), key)).get("workflowId");

        String other = portal.signup("Other", "other-" + UUID.randomUUID() + "@example.com", "correct-horse-9").developerId();
        String otherKey = developers.issueKey(other, "other").plaintextKey();
        assertThat(call("GET", "/api/gateway/workflows/" + id, null, otherKey).statusCode()).isEqualTo(404);
        assertThat(call("POST", "/api/gateway/workflows/" + id + "/cancel", Map.of(), otherKey).statusCode())
                .isEqualTo(404);
        // And another account's definition is simply not there to run.
        assertThat(call("POST", "/api/gateway/workflows/one-step/run", Map.of(), otherKey).statusCode())
                .isEqualTo(404);
    }

    @Test
    void withoutAKeyNothingAnswers() throws Exception {
        assertThat(call("POST", "/api/gateway/memory/store", Map.of("content", "x"), null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/gateway/memory/store", Map.of("content", "x"), "cnt_live_nope").statusCode())
                .isEqualTo(401);
    }

    @Test
    @SuppressWarnings("unchecked")
    void memoryStoredWithTheKeyIsRecalledByMeaningAndStaysInItsAccount() throws Exception {
        assertThat(call("POST", "/api/gateway/memory/store",
                Map.of("scope", "customer-7", "content", "Prefers refunds to store credit", "tier", "LONG_TERM"), key)
                .statusCode()).isEqualTo(200);
        HttpResponse<String> recalled = call("POST", "/api/gateway/memory/retrieve",
                Map.of("scope", "customer-7", "query", "refund or store credit?"), key);
        assertThat(recalled.statusCode()).isEqualTo(200);
        List<Map<String, Object>> rows = json.readValue(recalled.body(), List.class);
        assertThat(rows).isNotEmpty();
        assertThat(recalled.body()).contains("Prefers refunds");

        String other = portal.signup("Other", "mem-" + UUID.randomUUID() + "@example.com", "correct-horse-9").developerId();
        String otherKey = developers.issueKey(other, "other").plaintextKey();
        HttpResponse<String> theirs = call("POST", "/api/gateway/memory/retrieve",
                Map.of("scope", "customer-7", "query", "refund"), otherKey);
        assertThat(theirs.body()).doesNotContain("Prefers refunds");
    }

    @Test
    void theLoopDetectorAnswersAnAgentFrameworkAndHaltsItInHaltMode() throws Exception {
        loops.configure(dev, true, "HALT");
        List<String> steps = List.of("lookup_order(BC-1)", "lookup_order(BC-1)", "lookup_order(BC-1)",
                "lookup_order(BC-1)", "lookup_order(BC-1)", "lookup_order(BC-1)");
        HttpResponse<String> r = call("POST", "/api/gateway/loops/inspect",
                Map.of("workflowId", "agent-1", "steps", steps), key);
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(read(r).get("looping")).isEqualTo(true);
    }

    @Test
    void anAnswerCanBeRatedWithTheKey() throws Exception {
        HttpResponse<String> r = call("POST", "/api/gateway/feedback",
                Map.of("requestRef", "req_1", "score", 1, "comment", "solved it"), key);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(call("POST", "/api/gateway/feedback", Map.of("score", 1), key).statusCode()).isEqualTo(400);
    }
}
