package io.continuum.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.continuum.common.Json;
import io.continuum.core.engine.ActivityWorker;
import io.continuum.core.engine.WorkflowEngine;
import io.continuum.core.engine.WorkflowWorker;
import io.continuum.core.outbox.OutboxDispatcher;
import io.continuum.net.GuardedHttpSender;
import io.continuum.persistence.entity.WorkflowStatus;
import io.continuum.persistence.repository.OutboxRepository;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import io.continuum.vault.AesGcmCipher;
import io.continuum.webhook.WebhookService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A finished run is announced to its owner's endpoint once, signed, with an
 * idempotency key that survives retries — through the same outbox as every
 * other side effect.
 */
@Import(ItWorkflows.class)
class WebhookIT extends PostgresIT {

    @Autowired WebhookService webhooks;
    @Autowired WorkflowEngine engine;
    @Autowired WorkflowWorker workflowWorker;
    @Autowired ActivityWorker activityWorker;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired WorkflowInstanceRepository instances;
    @Autowired OutboxRepository outbox;
    @Autowired AesGcmCipher cipher;
    @Autowired JdbcTemplate jdbc;
    @Autowired Json json;
    @Autowired ObjectMapper mapper;

    record Received(String body, String signature, String key, String event) {
    }

    private HttpServer receiver;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    /** Status the receiver answers with, first to last; the last repeats. */
    private volatile List<Integer> answers = List.of(200);
    private final AtomicInteger calls = new AtomicInteger();
    private String url;
    private final String dev = "it-dev-" + UUID.randomUUID();

    @BeforeEach
    void listen() throws Exception {
        jdbc.update("UPDATE activity_tasks SET status = 'FAILED' WHERE status IN ('PENDING', 'RUNNING')");
        jdbc.update("UPDATE workflow_tasks SET status = 'COMPLETED' WHERE status IN ('PENDING', 'RUNNING')");
        jdbc.update("UPDATE outbox SET status = 'FAILED' WHERE status = 'PENDING'");
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", ex -> {
            received.add(new Received(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    ex.getRequestHeaders().getFirst("Continuum-Signature"),
                    ex.getRequestHeaders().getFirst("Idempotency-Key"),
                    ex.getRequestHeaders().getFirst("Continuum-Event")));
            int n = calls.getAndIncrement();
            int status = answers.get(Math.min(n, answers.size() - 1));
            byte[] out = (status == 200 ? "ok" : "not now").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        receiver.start();
        url = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
    }

    @AfterEach
    void close() {
        receiver.stop(0);
    }

    private String runToEnd() throws Exception {
        String id = "it-" + UUID.randomUUID();
        engine.startWorkflow("it.twoStep", json.write(new ItWorkflows.Echo("hook", 5)), id, dev);
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            workflowWorker.poll();
            activityWorker.poll();
            dispatcher.poll();
            if (instances.findById(id).orElseThrow().getStatus() != WorkflowStatus.RUNNING
                    && activityWorker.inFlight() == 0) {
                break;
            }
            Thread.sleep(50);
        }
        dispatcher.poll();
        return id;
    }

    /** Makes queued webhook messages due now, skipping the retry backoff, and dispatches. */
    private void retryNow() {
        jdbc.update("UPDATE outbox SET visible_at = now() WHERE destination = 'webhook' AND status = 'PENDING'");
        dispatcher.poll();
    }

    @Test
    void aFinishedRunIsDeliveredOnceSignedAndWithItsIdempotencyKey() throws Exception {
        WebhookService.Created created = webhooks.create(dev, url, "orders", List.of("workflow.completed"));

        String id = runToEnd();
        retryNow();
        retryNow();

        assertThat(received).hasSize(1);
        Received r = received.get(0);
        JsonNode body = mapper.readTree(r.body());
        assertThat(body.path("type").asText()).isEqualTo("workflow.completed");
        assertThat(body.path("data").path("workflowId").asText()).isEqualTo(id);
        assertThat(body.path("data").path("result").path("result").asText()).isEqualTo("hook!");
        assertThat(body.has("endpointId")).as("routing detail stays inside").isFalse();
        assertThat(r.event()).isEqualTo("workflow.completed");
        assertThat(r.key()).isEqualTo(body.path("id").asText());

        // The receiver's check: HMAC-SHA256 of "<t>.<body>" with the secret.
        long t = Long.parseLong(r.signature().replaceAll("t=(\\d+),.*", "$1"));
        assertThat(r.signature()).isEqualTo(WebhookService.signature(created.secret(), t, r.body()));
        assertThat(r.signature()).isNotEqualTo(WebhookService.signature("whsec_wrong", t, r.body()));

        List<WebhookService.Delivery> log = webhooks.deliveries(dev, created.endpoint().id(), 10);
        assertThat(log).hasSize(1);
        assertThat(log.get(0).statusCode()).isEqualTo(200);
    }

    @Test
    void anEndpointThatFailsIsRetriedWithTheSameKeyUntilItAnswers() throws Exception {
        answers = List.of(500, 503, 200);
        WebhookService.Created created = webhooks.create(dev, url, null, null);

        runToEnd();
        retryNow();
        retryNow();
        retryNow();

        assertThat(received).hasSize(3);
        assertThat(received.stream().map(Received::key).distinct()).hasSize(1);
        List<Integer> statuses = webhooks.deliveries(dev, created.endpoint().id(), 10).stream()
                .map(WebhookService.Delivery::statusCode).toList();
        assertThat(statuses).containsExactly(200, 503, 500);
        assertThat(outbox.findByIdempotencyKey(received.get(0).key()).orElseThrow().getStatus().name()).isEqualTo("SENT");
    }

    @Test
    void onlySubscribedEventsGoOutAndOnlyToTheRunsOwner() throws Exception {
        webhooks.create(dev, url, null, List.of("workflow.failed"));
        webhooks.create("it-someone-else-" + UUID.randomUUID(), url, null, null);

        runToEnd();
        retryNow();

        assertThat(received).isEmpty();
    }

    @Test
    void aTestPingGoesThroughTheOutboxAndItsSecretCanBeRotated() throws Exception {
        WebhookService.Created created = webhooks.create(dev, url, null, null);
        webhooks.ping(dev, created.endpoint().id());
        dispatcher.poll();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).event()).isEqualTo("ping");

        String rotated = webhooks.rotateSecret(dev, created.endpoint().id());
        webhooks.ping(dev, created.endpoint().id());
        dispatcher.poll();
        Received second = received.get(1);
        long t = Long.parseLong(second.signature().replaceAll("t=(\\d+),.*", "$1"));
        assertThat(second.signature()).isEqualTo(WebhookService.signature(rotated, t, second.body()));
        assertThat(second.signature()).isNotEqualTo(WebhookService.signature(created.secret(), t, second.body()));
    }

    @Test
    void anotherAccountsEndpointReadsAsMissingAndPrivateAddressesAreRefused() {
        WebhookService.Created created = webhooks.create(dev, url, null, null);
        assertThatThrownBy(() -> webhooks.deliveries("it-intruder", created.endpoint().id(), 5))
                .isInstanceOf(io.continuum.portal.RequestScope.NotFoundException.class);

        WebhookService guarded = new WebhookService(jdbc, outbox, cipher, new GuardedHttpSender(false), mapper);
        assertThatThrownBy(() -> guarded.create(dev, "http://127.0.0.1:9/hook", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private address");
        assertThatThrownBy(() -> guarded.create(dev, "ftp://example.com/hook", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
