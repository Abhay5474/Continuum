package io.continuum.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.continuum.core.outbox.OutboxSink;
import io.continuum.net.GuardedHttpSender;
import io.continuum.persistence.entity.OutboxEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delivers {@code webhook} outbox messages: a signed POST to the endpoint.
 *
 * <p>A 2xx answer is delivery. Anything else — or no answer — throws, and the
 * outbox dispatcher retries with backoff, with the same idempotency key, until
 * its attempt limit. Every attempt is recorded, so the owner can see what their
 * endpoint said. An endpoint deleted or switched off since the event was queued
 * is not called; the message is settled rather than retried for nobody.
 */
@Component
public class WebhookSink implements OutboxSink {

    static final int TIMEOUT_SECONDS = 10;

    private final WebhookService webhooks;
    private final GuardedHttpSender sender;
    private final ObjectMapper mapper;

    public WebhookSink(WebhookService webhooks, GuardedHttpSender sender, ObjectMapper mapper) {
        this.webhooks = webhooks;
        this.sender = sender;
        this.mapper = mapper;
    }

    @Override
    public String destination() {
        return WebhookService.DESTINATION;
    }

    @Override
    public void deliver(OutboxEntity message) throws Exception {
        JsonNode envelope = mapper.readTree(message.getPayload());
        long endpointId = envelope.path("endpointId").asLong();
        WebhookService.Target target = webhooks.target(endpointId);
        String event = message.getEventType();
        String key = message.getIdempotencyKey();
        if (target == null || !target.enabled()) {
            webhooks.recordAttempt(endpointId, message.getId(), event, key, message.getAttempts(), null,
                    target == null ? "endpoint deleted; not sent" : "endpoint switched off; not sent", 0);
            return;
        }
        // What the receiver gets: the envelope without our routing field.
        ObjectNode body = ((ObjectNode) envelope).deepCopy();
        body.remove("endpointId");
        String raw = mapper.writeValueAsString(body);
        long now = System.currentTimeMillis() / 1000;

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Idempotency-Key", key);
        headers.put("Continuum-Event", event);
        headers.put("Continuum-Signature", WebhookService.signature(target.secret(), now, raw));

        long started = System.nanoTime();
        GuardedHttpSender.Result result;
        try {
            result = sender.send(target.url(), "POST", headers, raw, TIMEOUT_SECONDS);
        } catch (Exception e) {
            webhooks.recordAttempt(endpointId, message.getId(), event, key, message.getAttempts(), null,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), ms(started));
            throw e;
        }
        boolean ok = result.status() / 100 == 2;
        webhooks.recordAttempt(endpointId, message.getId(), event, key, message.getAttempts(), result.status(),
                ok ? null : snippet(result.body()), ms(started));
        if (!ok) {
            throw new IllegalStateException("Webhook endpoint answered " + result.status());
        }
    }

    private static long ms(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static String snippet(String body) {
        if (body == null) {
            return null;
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }
}
