package io.continuum.declarative;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Built-in stand-ins for the services a workflow step normally calls, so a
 * workflow can be authored and run before any real service exists.
 *
 * <p>A step whose url is {@code sandbox://<service>/<action>} is answered here,
 * inside Continuum, instead of over the network: no server to start, no private
 * address to allow, and the same answer on every deployment. Everything else
 * about the step is real — it is a durable activity, recorded, retried and
 * replayed like any HTTP step, and its compensation runs on rollback.
 *
 * <p>Answers are deterministic for a given idempotency key, so a recovered run
 * sees the same references it saw before the crash. Three knobs in the step body
 * let a demonstration show the engine's behaviour on purpose:
 * <ul>
 *   <li>{@code "failTimes": n} — the first n attempts fail with a 503, so the run
 *       timeline shows retries with backoff before the step succeeds;</li>
 *   <li>{@code "fail": true} — the call is declined (422, not retried), so the run
 *       fails and the completed steps' compensations run;</li>
 *   <li>{@code "delayMs": n} — the answer takes up to 5 s, to make a step visible.</li>
 * </ul>
 */
public final class SandboxServices {

    public static final String SCHEME = "sandbox://";

    private SandboxServices() {
    }

    public static boolean handles(String url) {
        return url != null && url.regionMatches(true, 0, SCHEME, 0, SCHEME.length());
    }

    /** A sandbox answer: an HTTP-like status and a JSON-shaped body. */
    public record Answer(int status, Map<String, Object> body) {
    }

    public static Answer call(String url, String method, Object body, String idempotencyKey, int attempt)
            throws InterruptedException {
        String path = url.substring(SCHEME.length());
        int q = path.indexOf('?');
        Map<String, String> query = new LinkedHashMap<>();
        if (q >= 0) {
            for (String kv : path.substring(q + 1).split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    query.put(decode(kv.substring(0, eq)), decode(kv.substring(eq + 1)));
                }
            }
            path = path.substring(0, q);
        }
        path = path.replaceAll("^/+|/+$", "");
        String service = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
        String action = path.contains("/") ? path.substring(path.indexOf('/') + 1) : "call";

        Map<String, Object> in = new LinkedHashMap<>(query);
        if (body instanceof Map<?, ?> m) {
            m.forEach((k, v) -> in.put(String.valueOf(k), v));
        }

        long delay = Math.min(5000, Math.max(0, asLong(in.get("delayMs"), 0)));
        if (delay > 0) {
            Thread.sleep(delay);
        }
        long failTimes = asLong(in.get("failTimes"), 0);
        if (attempt <= failTimes) {
            return new Answer(503, Map.of("error", "unavailable",
                    "message", "sandbox " + service + "/" + action + " is unavailable (attempt " + attempt
                            + " of the " + failTimes + " set to fail by failTimes)"));
        }
        if (truthy(in.get("fail"))) {
            return new Answer(422, Map.of("error", "declined",
                    "message", "sandbox " + service + "/" + action + " declined the request (\"fail\": true)"));
        }

        String ref = reference(service, action, idempotencyKey);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("service", service);
        out.put("action", action);
        out.put("method", method);
        out.put("reference", ref);
        String a = (service + "/" + action).toLowerCase();
        switch (a) {
            case "orders/verify", "orders/get" -> {
                out.put("orderId", in.getOrDefault("orderId", "BC-1001"));
                out.put("valid", true);
                out.put("sku", in.getOrDefault("sku", "MUG-CERAMIC-350"));
                out.put("qty", in.getOrDefault("qty", 1));
                out.put("amount", in.getOrDefault("amount", 499));
                out.put("customerEmail", in.getOrDefault("customerEmail", "priya@example.com"));
            }
            case "stock/reserve", "inventory/reserve" -> {
                out.put("holdId", "hold-" + ref.substring(ref.length() - 8));
                out.put("sku", in.getOrDefault("sku", "MUG-CERAMIC-350"));
                out.put("qty", in.getOrDefault("qty", 1));
            }
            case "payments/charge" -> {
                out.put("paymentId", "pay-" + ref.substring(ref.length() - 8));
                out.put("amount", in.getOrDefault("amount", 499));
                out.put("currency", "INR");
                out.put("paid", true);
            }
            case "payments/refund" -> {
                out.put("refundId", "rfnd-" + ref.substring(ref.length() - 8));
                out.put("amount", in.getOrDefault("amount", 499));
                out.put("currency", "INR");
            }
            case "courier/book", "courier/pickup" -> out.put("trackingNumber", "TRK" + ref.substring(ref.length() - 8).toUpperCase());
            case "email/send", "notify/send" -> out.put("messageId", "msg-" + ref.substring(ref.length() - 8));
            case "risk/score" -> {
                out.put("score", 12);
                out.put("approved", true);
            }
            default -> {
                // Any other service/action simply succeeds and echoes what it was sent.
            }
        }
        out.put("received", in);
        return new Answer(200, out);
    }

    private static String reference(String service, String action, String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest((service + "/" + action + "|" + key).getBytes(StandardCharsets.UTF_8));
            return service + "-" + HexFormat.of().formatHex(h, 0, 6);
        } catch (Exception e) {
            return service + "-" + Integer.toHexString((service + action + key).hashCode());
        }
    }

    private static boolean truthy(Object v) {
        return v instanceof Boolean b ? b : v != null && "true".equalsIgnoreCase(String.valueOf(v).trim());
    }

    private static long asLong(Object v, long dflt) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? dflt : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
