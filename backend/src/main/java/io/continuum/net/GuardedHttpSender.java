package io.continuum.net;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * The one place Continuum makes an outbound call to an address a customer chose.
 *
 * <p>Two consumers need this: declarative workflow steps, and specialist model
 * invocations. They must not each carry their own copy of the guard — a second
 * implementation is a second thing to forget to fix, and the whole point of the
 * guard is that it holds everywhere.
 *
 * <p>Three protections, each closing a specific hole:
 *
 * <ul>
 *   <li><b>Private-address refusal.</b> Customer-supplied URLs make Continuum a
 *       confused deputy able to reach the deployment's own network and cloud
 *       metadata endpoints.</li>
 *   <li><b>Address pinning.</b> Checking a hostname then reconnecting by name
 *       lets a hostile nameserver answer differently the second time; the
 *       connection goes to the address that was actually checked. See
 *       {@link PinnedDnsResolver}.</li>
 *   <li><b>Bounded reads.</b> A response is streamed and truncated rather than
 *       materialised, so one endpoint cannot exhaust the heap the whole engine
 *       shares.</li>
 * </ul>
 *
 * <p>Redirects are never followed: a redirect could bounce an approved public
 * URL onto an internal address after the check has passed.
 */
@Component
public class GuardedHttpSender {

    /** Generous for legitimate JSON, small enough to bound the damage. */
    public static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final HttpClient client;
    private final boolean allowPrivateTargets;

    public GuardedHttpSender(
            @Value("${continuum.declarative.allow-private-targets:false}") boolean allowPrivate) {
        this.allowPrivateTargets = allowPrivate;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** A refused target, or a response the caller should not retry. */
    public static class NonRetryable extends RuntimeException
            implements io.continuum.core.activity.NonRetryableFailure {
        public NonRetryable(String message) {
            super(message);
        }
    }

    /** What came back: the status and at most {@link #MAX_RESPONSE_BYTES} of body. */
    public record Result(int status, String body) {
    }

    /**
     * Sends a request, refusing targets inside the deployment's own network.
     *
     * @param headers already filtered by the caller for anything it controls
     */
    public Result send(String url, String method, Map<String, String> headers, String body,
                       int timeoutSeconds) throws IOException, InterruptedException {
        Target target;
        try {
            target = resolve(url);
        } catch (URISyntaxException | UnknownHostException e) {
            throw new NonRetryable("Could not resolve target: " + e.getMessage());
        }

        HttpRequest.Builder b = HttpRequest.newBuilder(target.uri())
                .timeout(Duration.ofSeconds(Math.max(1, Math.min(300, timeoutSeconds))))
                .header("User-Agent", "Continuum/1.0");
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (k != null && v != null) {
                    b.header(k, v);
                }
            });
        }

        String verb = method == null ? "POST" : method.toUpperCase();
        switch (verb) {
            case "GET" -> b.GET();
            case "DELETE" -> b.DELETE();
            case "PUT" -> b.PUT(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            case "PATCH" -> b.method("PATCH", HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            default -> b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }

        boolean pinned = target.pin();
        try {
            HttpResponse<InputStream> res = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new Result(res.statusCode(), readBounded(res.body()));
        } finally {
            if (pinned) {
                target.release();
            }
        }
    }

    /** A vetted destination and the single address approved for it. */
    public record Target(URI uri, String host, InetAddress address) {

        /** @return whether the pin is in force; false when the SPI is absent */
        public boolean pin() {
            return address != null && PinnedDnsResolver.pin(host, address);
        }

        public void release() {
            PinnedDnsResolver.unpin(host);
        }
    }

    /**
     * Validates a URL and returns the address the connection must use.
     *
     * <p>Carrying the address forward rather than re-deriving it at connect time
     * is the whole mitigation — see the class comment.
     */
    public Target resolve(String url) throws URISyntaxException, UnknownHostException {
        URI uri = new URI(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new NonRetryable("Target url must be http or https: " + url);
        }
        if (uri.getHost() == null) {
            throw new NonRetryable("Target url has no host: " + url);
        }
        if (allowPrivateTargets) {
            return new Target(uri, uri.getHost(), null);
        }
        InetAddress[] resolved = InetAddress.getAllByName(uri.getHost());
        for (InetAddress addr : resolved) {
            if (isPrivate(addr)) {
                throw new NonRetryable("Target url resolves to a private address, which is not allowed: "
                        + uri.getHost());
            }
        }
        return new Target(uri, uri.getHost(), resolved.length > 0 ? resolved[0] : null);
    }

    /** Addresses inside the deployment's own network, in any of their guises. */
    public static boolean isPrivate(InetAddress addr) {
        return addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isMulticastAddress()
                || isUniqueLocal(addr);
    }

    /** IPv6 unique-local (fc00::/7), which the JDK does not classify as site-local. */
    private static boolean isUniqueLocal(InetAddress addr) {
        byte[] a = addr.getAddress();
        return a.length == 16 && (a[0] & 0xFE) == 0xFC;
    }

    private static String readBounded(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(chunk)) > 0) {
                int room = MAX_RESPONSE_BYTES - total;
                if (room <= 0) {
                    break;
                }
                buf.write(chunk, 0, Math.min(n, room));
                total += n;
            }
            return buf.toString(StandardCharsets.UTF_8);
        }
    }
}
