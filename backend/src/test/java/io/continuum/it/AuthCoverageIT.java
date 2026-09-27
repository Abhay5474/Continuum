package io.continuum.it;

import io.continuum.portal.OperatorOnly;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every route the application serves, asked without credentials, refuses —
 * except the few that are public on purpose. Read from Spring's own handler
 * mappings, so a controller added tomorrow is checked without anyone
 * remembering to list it. And every {@link OperatorOnly} route refuses a
 * signed-in developer.
 */
class AuthCoverageIT extends PostgresIT {

    /** "METHOD path" → why anyone may call it. */
    static final Map<String, String> PUBLIC = Map.of(
            "GET /api/meta", "build info for the landing page",
            "GET /v1/models", "OpenAI SDKs list models before sending a key; names only",
            "POST /api/portal/developer/signup", "creating an account",
            "POST /api/portal/developer/login", "signing in",
            "POST /api/portal/operator/login", "checks the admin token itself",
            "GET /api/portal/developer/account/invites/preview", "an invitee has no session yet",
            "POST /api/portal/developer/account/invites/accept", "an invitee has no session yet");

    @LocalServerPort int port;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @Autowired io.continuum.portal.RateLimiter limiter;

    private final HttpClient http = HttpClient.newHttpClient();

    record Route(String method, String path, HandlerMethod handler) {
        String key() {
            return method + " " + path;
        }
    }

    private List<Route> apiRoutes() {
        List<Route> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            Set<String> paths = e.getKey().getPatternValues();
            Set<RequestMethod> methods = e.getKey().getMethodsCondition().getMethods();
            for (String path : paths) {
                if (!path.startsWith("/api/") && !path.startsWith("/v1/")) {
                    continue;
                }
                for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    out.add(new Route(m.name(), path, e.getValue()));
                }
            }
        }
        return out;
    }

    private int call(Route r, String token) throws Exception {
        // Hundreds of calls from one address trip the anonymous rate limit,
        // and a 429 says nothing about authentication. Start each call fresh.
        ((Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(limiter, "buckets")).clear();
        String concrete = r.path().replaceAll("\\{[^}]*}", "1");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + concrete))
                .header("Content-Type", "application/json")
                .method(r.method(), r.method().equals("GET") || r.method().equals("DELETE")
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString("{}"));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void everyRouteRefusesAnAnonymousCallerUnlessItIsPublicOnPurpose() throws Exception {
        List<Route> routes = apiRoutes();
        assertThat(routes).hasSizeGreaterThan(200);
        List<String> open = new ArrayList<>();
        for (Route r : routes) {
            if (PUBLIC.containsKey(r.key())) {
                continue;
            }
            int status = call(r, null);
            if (status != 401 && status != 403) {
                // Anything else — 200, 400, 404, 500 — means the request got
                // past authentication to the handler.
                open.add(r.key() + " → " + status);
            }
        }
        assertThat(open).as("routes that answered an anonymous caller").isEmpty();
    }

    @Test
    void everyOperatorOnlyRouteRefusesADeveloper() throws Exception {
        String email = "auth-it-" + UUID.randomUUID() + "@example.com";
        HttpResponse<String> signup = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/portal/developer/signup"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"name\":\"Auth IT\",\"email\":\"" + email + "\",\"password\":\"correct-horse-battery-9\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(signup.statusCode()).isEqualTo(200);
        String token = signup.body().replaceAll(".*\"sessionToken\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        List<Route> operatorOnly = apiRoutes().stream().filter(r ->
                r.handler().hasMethodAnnotation(OperatorOnly.class)
                        || r.handler().getBeanType().isAnnotationPresent(OperatorOnly.class)).toList();
        assertThat(operatorOnly).isNotEmpty();
        List<String> allowed = new ArrayList<>();
        for (Route r : operatorOnly) {
            int status = call(r, token);
            if (status != 403) {
                allowed.add(r.key() + " → " + status);
            }
        }
        assertThat(allowed).as("operator-only routes that let a developer through").isEmpty();
    }
}
