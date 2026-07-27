package io.continuum.declarative;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import io.continuum.net.GuardedHttpSender;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls a customer endpoint on behalf of a declarative workflow step.
 *
 * <p>This is where all the non-determinism lives, which is what lets the
 * surrounding workflow replay exactly: the engine records this activity's result
 * and returns it verbatim on recovery rather than calling the endpoint again.
 *
 * <p>Every request carries a stable {@code Idempotency-Key} derived from the
 * workflow and step id, so if the engine does retry after a timeout, the
 * receiving service can recognise the duplicate. That is how "exactly-once side
 * effects" reaches a customer's own systems.
 *
 * <p>The network guard — private-address refusal, address pinning against DNS
 * rebinding, bounded reads — lives in {@link GuardedHttpSender}, shared with the
 * specialist invoker. Two copies of a security control is one copy too many.
 */
@Component
public class HttpStepActivity implements Activity {

    public static final String TYPE = "declarative.httpStep";

    private final ObjectMapper mapper;
    private final GuardedHttpSender sender;

    public HttpStepActivity(ObjectMapper mapper, GuardedHttpSender sender) {
        this.mapper = mapper;
        this.sender = sender;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object execute(String inputJson, ActivityContext ctx) throws Exception {
        Input in = mapper.readValue(inputJson, Input.class);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Idempotency-Key", in.idempotencyKey());
        if (in.headers() != null) {
            in.headers().forEach((k, v) -> {
                if (k != null && v != null && !isRestrictedHeader(k)) {
                    headers.put(k, v);
                }
            });
        }

        String body = in.body() == null ? null : mapper.writeValueAsString(in.body());
        String method = in.method() == null ? "POST" : in.method().toUpperCase();
        // A body-carrying verb with no body still needs valid JSON, or a strict
        // receiver rejects the request before reading anything.
        if (body == null && !method.equals("GET") && !method.equals("DELETE")) {
            body = "{}";
        }

        GuardedHttpSender.Result res;
        try {
            res = sender.send(in.url(), method, headers, body, in.timeoutSeconds());
        } catch (GuardedHttpSender.NonRetryable e) {
            // A refused target will be refused identically next time; retrying
            // it only burns the step's budget.
            throw new NonRetryable(e.getMessage());
        }
        int code = res.status();

        // 4xx is the caller's fault and will not fix itself, so it fails the step
        // immediately instead of burning the retry budget. 5xx and timeouts throw,
        // which is what the engine retries.
        if (code >= 500) {
            throw new IllegalStateException("Step target returned " + code + ": " + truncate(res.body()));
        }
        if (code >= 400) {
            throw new NonRetryable("Step target returned " + code + ": " + truncate(res.body()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", code);
        out.put("body", parse(res.body()));
        return out;
    }

    /** A failure the engine should not retry. */
    public static class NonRetryable extends RuntimeException
            implements io.continuum.core.activity.NonRetryableFailure {
        public NonRetryable(String message) {
            super(message);
        }
    }

    /**
     * Kept for the guard's own tests, which assert on this class rather than on
     * the sender it now delegates to.
     */
    GuardedHttpSender.Target resolveTarget(String url) throws Exception {
        return sender.resolve(url);
    }

    /** Headers the engine controls; a step must not be able to forge them. */
    private static boolean isRestrictedHeader(String name) {
        String n = name.toLowerCase();
        return n.equals("host") || n.equals("content-length") || n.equals("connection")
                || n.equals("idempotency-key") || n.startsWith("continuum-");
    }

    private Object parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(body, Object.class);
        } catch (Exception e) {
            // Not JSON — hand back the text so the step still has something usable.
            return truncate(body);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 4000 ? s : s.substring(0, 4000) + "…";
    }

    public record Input(String url, String method, Map<String, String> headers, Object body,
                        int timeoutSeconds, String idempotencyKey) {
    }
}
