package io.continuum.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.net.GuardedHttpSender;
import io.continuum.persistence.entity.OutboxEntity;
import io.continuum.persistence.repository.OutboxRepository;
import io.continuum.vault.AesGcmCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Webhooks: an account's own endpoints, told when its workflows finish.
 *
 * <p>This is what makes the engine's exactly-once delivery usable from the
 * outside. An event is written to the transactional outbox in the same
 * transaction as the change it reports — a run cannot complete without its
 * "completed" event being queued, and the event cannot be queued for a run that
 * did not complete. The dispatcher then delivers it, retrying with backoff,
 * with an {@code Idempotency-Key} that is the same on every retry so the
 * receiver can drop a repeat, and a signature so it can tell the request came
 * from here.
 *
 * <h2>Verifying a delivery</h2>
 * Each request carries {@code Continuum-Signature: t=<unix seconds>,v1=<hex>},
 * where {@code v1} is HMAC-SHA256 of {@code "<t>.<raw body>"} keyed with the
 * endpoint's secret. Recompute it, compare in constant time, and reject a
 * {@code t} more than a few minutes old.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    public static final String DESTINATION = "webhook";

    /** Events an endpoint can subscribe to. */
    public static final List<String> EVENTS = List.of(
            "workflow.completed", "workflow.failed", "workflow.cancelled", "workflow.stuck");

    /** The most endpoints one account may have. */
    static final int MAX_ENDPOINTS = 10;

    private final JdbcTemplate jdbc;
    private final OutboxRepository outbox;
    private final AesGcmCipher cipher;
    private final GuardedHttpSender sender;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public WebhookService(JdbcTemplate jdbc, OutboxRepository outbox, AesGcmCipher cipher,
                          GuardedHttpSender sender, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.cipher = cipher;
        this.sender = sender;
        this.mapper = mapper;
    }

    /** An endpoint as the owner sees it. The secret is never part of it. */
    public record Endpoint(long id, String url, String description, List<String> events, boolean enabled,
                           Instant createdAt, Delivery lastDelivery) {
    }

    public record Delivery(long id, String event, String idempotencyKey, int attempt, Integer statusCode,
                           String error, long durationMs, Instant attemptedAt) {
        public boolean ok() {
            return statusCode != null && statusCode / 100 == 2;
        }
    }

    /** A new endpoint and its secret — shown this once. */
    public record Created(Endpoint endpoint, String secret) {
    }

    /** What the sink needs to deliver to an endpoint. */
    record Target(long id, String developerId, String url, String secret, boolean enabled) {
    }

    // ---------------------------------------------------------------- endpoints

    public List<Endpoint> list(String developerId) {
        List<Endpoint> out = new ArrayList<>();
        jdbc.query("SELECT id, url, description, events, enabled, created_at FROM webhook_endpoints "
                + "WHERE developer_id = ? ORDER BY id", rs -> {
            long id = rs.getLong("id");
            out.add(new Endpoint(id, rs.getString("url"), rs.getString("description"),
                    split(rs.getString("events")), rs.getBoolean("enabled"),
                    rs.getTimestamp("created_at").toInstant(), last(id)));
        }, developerId);
        return out;
    }

    @Transactional
    public Created create(String developerId, String url, String description, List<String> events) {
        require(developerId);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM webhook_endpoints WHERE developer_id = ?",
                Integer.class, developerId);
        if (count != null && count >= MAX_ENDPOINTS) {
            throw new IllegalArgumentException("An account can have at most " + MAX_ENDPOINTS + " webhook endpoints.");
        }
        String checked = checkUrl(url);
        String subscribed = String.join(",", checkEvents(events));
        String secret = newSecret();
        Long id = jdbc.queryForObject("INSERT INTO webhook_endpoints (developer_id, url, description, secret_encrypted, events) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class,
                developerId, checked, trim(description), cipher.encrypt(secret), subscribed);
        return new Created(find(developerId, id), secret);
    }

    @Transactional
    public Endpoint update(String developerId, long id, Boolean enabled, List<String> events, String description) {
        find(developerId, id);
        if (enabled != null) {
            jdbc.update("UPDATE webhook_endpoints SET enabled = ? WHERE id = ?", enabled, id);
        }
        if (events != null) {
            jdbc.update("UPDATE webhook_endpoints SET events = ? WHERE id = ?", String.join(",", checkEvents(events)), id);
        }
        if (description != null) {
            jdbc.update("UPDATE webhook_endpoints SET description = ? WHERE id = ?", trim(description), id);
        }
        return find(developerId, id);
    }

    /** A new secret; the old one stops working at once. */
    @Transactional
    public String rotateSecret(String developerId, long id) {
        find(developerId, id);
        String secret = newSecret();
        jdbc.update("UPDATE webhook_endpoints SET secret_encrypted = ? WHERE id = ?", cipher.encrypt(secret), id);
        return secret;
    }

    @Transactional
    public void delete(String developerId, long id) {
        find(developerId, id);
        jdbc.update("DELETE FROM webhook_deliveries WHERE endpoint_id = ?", id);
        jdbc.update("DELETE FROM webhook_endpoints WHERE id = ?", id);
    }

    /** Queues a {@code ping} to one endpoint, through the same outbox as real events. */
    @Transactional
    public String ping(String developerId, long id) {
        find(developerId, id);
        String key = "whk_ping_" + id + "_" + UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "Test delivery from Continuum");
        enqueueTo(id, "ping", null, key, data);
        return key;
    }

    public List<Delivery> deliveries(String developerId, long id, int limit) {
        find(developerId, id);
        return jdbc.query("SELECT id, event, idempotency_key, attempt, status_code, error, duration_ms, attempted_at "
                        + "FROM webhook_deliveries WHERE endpoint_id = ? ORDER BY id DESC LIMIT ?",
                (rs, n) -> delivery(rs), id, Math.max(1, Math.min(limit, 100)));
    }

    // ------------------------------------------------------------------ events

    /**
     * Queues {@code event} for every enabled endpoint of {@code developerId}
     * that subscribes to it. Call inside the transaction that makes the change,
     * so the event is queued if and only if the change commits.
     */
    public void enqueue(String developerId, String event, String workflowId, Map<String, Object> data) {
        enqueue(developerId, event, workflowId, workflowId, data);
    }

    /**
     * As above, with {@code occurrence} naming this occurrence of the event
     * when a run can have more than one (a run parked, resumed, parked again).
     */
    public void enqueue(String developerId, String event, String workflowId, String occurrence,
                        Map<String, Object> data) {
        if (developerId == null) {
            return;
        }
        try {
            List<Long> ids = jdbc.queryForList("SELECT id FROM webhook_endpoints WHERE developer_id = ? AND enabled "
                            + "AND (',' || events || ',') LIKE ?", Long.class, developerId, "%," + event + ",%");
            for (Long id : ids) {
                // One per endpoint and event and run: the same key on every
                // retry, and a second enqueue of the same event is a no-op.
                String key = "whk_" + id + "_" + event + "_" + occurrence;
                if (outbox.findByIdempotencyKey(key).isEmpty()) {
                    enqueueTo(id, event, workflowId, key, data);
                }
            }
        } catch (RuntimeException e) {
            // Queued in the caller's transaction: a failure here would fail the
            // change being reported. Log it rather than lose the run.
            log.warn("Could not queue webhook {} for {}: {}", event, workflowId, e.getMessage());
        }
    }

    private void enqueueTo(long endpointId, String event, String workflowId, String key, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("endpointId", endpointId);
        envelope.put("id", key);
        envelope.put("type", event);
        envelope.put("createdAt", Instant.now().toString());
        envelope.put("data", data);
        try {
            outbox.save(new OutboxEntity(workflowId, DESTINATION, event, mapper.writeValueAsString(envelope), key));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // -------------------------------------------------------------- delivery

    Target target(long id) {
        List<Target> t = jdbc.query("SELECT id, developer_id, url, secret_encrypted, enabled FROM webhook_endpoints WHERE id = ?",
                (rs, n) -> new Target(rs.getLong("id"), rs.getString("developer_id"), rs.getString("url"),
                        cipher.decrypt(rs.getString("secret_encrypted")), rs.getBoolean("enabled")), id);
        return t.isEmpty() ? null : t.get(0);
    }

    void recordAttempt(long endpointId, Long outboxId, String event, String key, int attempt,
                       Integer status, String error, long ms) {
        jdbc.update("INSERT INTO webhook_deliveries (endpoint_id, outbox_id, event, idempotency_key, attempt, "
                        + "status_code, error, duration_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                endpointId, outboxId, event, key, attempt, status,
                error == null ? null : error.length() > 1000 ? error.substring(0, 1000) : error, ms);
    }

    /** {@code t=<unix>,v1=<hex HMAC-SHA256("<t>.<body>")>}. */
    public static String signature(String secret, long unixSeconds, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((unixSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            return "t=" + unixSeconds + ",v1=" + HexFormat.of().formatHex(sig);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private Endpoint find(String developerId, long id) {
        require(developerId);
        List<Endpoint> found = new ArrayList<>();
        jdbc.query("SELECT id, url, description, events, enabled, created_at FROM webhook_endpoints "
                + "WHERE id = ? AND developer_id = ?", rs -> {
            found.add(new Endpoint(rs.getLong("id"), rs.getString("url"), rs.getString("description"),
                    split(rs.getString("events")), rs.getBoolean("enabled"),
                    rs.getTimestamp("created_at").toInstant(), null));
        }, id, developerId);
        if (found.isEmpty()) {
            // Someone else's endpoint reads exactly like no endpoint.
            throw new io.continuum.portal.RequestScope.NotFoundException("No webhook endpoint " + id);
        }
        Endpoint e = found.get(0);
        return new Endpoint(e.id(), e.url(), e.description(), e.events(), e.enabled(), e.createdAt(), last(id));
    }

    private Delivery last(long endpointId) {
        List<Delivery> d = jdbc.query("SELECT id, event, idempotency_key, attempt, status_code, error, duration_ms, attempted_at "
                + "FROM webhook_deliveries WHERE endpoint_id = ? ORDER BY id DESC LIMIT 1", (rs, n) -> delivery(rs), endpointId);
        return d.isEmpty() ? null : d.get(0);
    }

    private static Delivery delivery(java.sql.ResultSet rs) throws java.sql.SQLException {
        int status = rs.getInt("status_code");
        return new Delivery(rs.getLong("id"), rs.getString("event"), rs.getString("idempotency_key"),
                rs.getInt("attempt"), rs.wasNull() ? null : status, rs.getString("error"),
                rs.getLong("duration_ms"), rs.getTimestamp("attempted_at").toInstant());
    }

    private String checkUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("A webhook needs a URL.");
        }
        String u = url.trim();
        if (u.length() > 2000) {
            throw new IllegalArgumentException("That URL is too long.");
        }
        try {
            // Same guard as every other customer-chosen address: no private
            // targets, no metadata endpoints, http or https only.
            sender.resolve(u);
        } catch (GuardedHttpSender.NonRetryable e) {
            throw new IllegalArgumentException(e.getMessage());
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not resolve " + u + ": " + e.getMessage());
        }
        return u;
    }

    private static List<String> checkEvents(List<String> events) {
        if (events == null || events.isEmpty()) {
            return EVENTS;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String e : events) {
            if (!EVENTS.contains(e)) {
                throw new IllegalArgumentException("Unknown event '" + e + "'. Choose from " + EVENTS + ".");
            }
            out.add(e);
        }
        return List.copyOf(out);
    }

    private static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : List.of(csv.split(","));
    }

    private static String trim(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private String newSecret() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static void require(String developerId) {
        if (developerId == null) {
            throw new io.continuum.portal.RequestScope.ForbiddenException("Webhooks belong to an account; sign in as one.");
        }
    }
}
