package io.continuum.declarative;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 */
@Component
public class HttpStepActivity implements Activity {

    public static final String TYPE = "declarative.httpStep";
    private static final Logger log = LoggerFactory.getLogger(HttpStepActivity.class);

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final boolean allowPrivateTargets;

    public HttpStepActivity(ObjectMapper mapper,
                            @Value("${continuum.declarative.allow-private-targets:false}") boolean allowPrivate) {
        this.mapper = mapper;
        this.allowPrivateTargets = allowPrivate;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // Redirects are not followed: a redirect could bounce an allowed
                // public URL onto an internal address after the check has passed.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object execute(String inputJson, ActivityContext ctx) throws Exception {
        Input in = mapper.readValue(inputJson, Input.class);
        URI uri = validateTarget(in.url());

        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(1, Math.min(300, in.timeoutSeconds()))))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", in.idempotencyKey())
                .header("User-Agent", "Continuum/1.0");

        if (in.headers() != null) {
            in.headers().forEach((k, v) -> {
                if (k != null && v != null && !isRestrictedHeader(k)) {
                    b.header(k, v);
                }
            });
        }

        String body = in.body() == null ? null : mapper.writeValueAsString(in.body());
        String method = in.method() == null ? "POST" : in.method().toUpperCase();
        switch (method) {
            case "GET" -> b.GET();
            case "DELETE" -> b.DELETE();
            case "PUT" -> b.PUT(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
            case "PATCH" -> b.method("PATCH", HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
            default -> b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        }

        HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        int code = res.statusCode();

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
     * Blocks requests aimed at the deployment's own network. Step URLs come from
     * customers, so without this the engine would be a confused deputy able to
     * reach internal services and cloud metadata endpoints.
     */
    URI validateTarget(String url) throws URISyntaxException, UnknownHostException {
        URI uri = new URI(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new NonRetryable("Step url must be http or https: " + url);
        }
        if (uri.getHost() == null) {
            throw new NonRetryable("Step url has no host: " + url);
        }
        if (allowPrivateTargets) {
            return uri;
        }
        for (InetAddress addr : InetAddress.getAllByName(uri.getHost())) {
            if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress() || addr.isMulticastAddress()
                    || isUniqueLocal(addr)) {
                throw new NonRetryable("Step url resolves to a private address, which is not allowed: "
                        + uri.getHost());
            }
        }
        return uri;
    }

    /** IPv6 unique-local (fc00::/7), which the JDK does not classify as site-local. */
    private static boolean isUniqueLocal(InetAddress addr) {
        byte[] a = addr.getAddress();
        return a.length == 16 && (a[0] & 0xFE) == 0xFC;
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
