package io.continuum.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's API types are generated from {@code frontend/openapi.json}.
 * This keeps that file equal to what the backend actually serves, so a renamed
 * or removed field fails this build — and, once the types are regenerated, the
 * frontend's — rather than showing up as a blank on a page.
 *
 * <p>To update the file after changing a DTO:
 * {@code CONTINUUM_WRITE_OPENAPI=true mvn verify -Dit.test=OpenApiIT}, then
 * {@code npm run gen:api} in {@code frontend}.
 */
class OpenApiIT extends PostgresIT {

    static final Path COMMITTED = Path.of("..", "frontend", "openapi.json");

    @LocalServerPort int port;

    @Test
    void theCommittedDescriptionMatchesWhatTheBackendServes() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);

        String served = normalise(r.body());
        if ("true".equalsIgnoreCase(System.getenv("CONTINUUM_WRITE_OPENAPI"))) {
            Files.writeString(COMMITTED, served, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(COMMITTED)).as("frontend/openapi.json exists").isTrue();
        assertThat(Files.readString(COMMITTED, StandardCharsets.UTF_8))
                .as("frontend/openapi.json is out of date: run CONTINUUM_WRITE_OPENAPI=true mvn verify "
                        + "-Dit.test=OpenApiIT, then npm run gen:api in frontend")
                .isEqualTo(served);
    }

    /** Stable text: keys sorted, pretty-printed, and the random test port removed. */
    static String normalise(String json) throws Exception {
        ObjectMapper m = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        JsonNode tree = m.readTree(json);
        ((ObjectNode) tree).remove("servers");
        Object sorted = sort(m.convertValue(tree, Object.class));
        return m.writeValueAsString(sorted) + "\n";
    }

    @SuppressWarnings("unchecked")
    private static Object sort(Object v) {
        if (v instanceof Map<?, ?> map) {
            TreeMap<String, Object> out = new TreeMap<>();
            ((Map<String, Object>) map).forEach((k, x) -> out.put(k, sort(x)));
            return out;
        }
        if (v instanceof java.util.List<?> list) {
            return list.stream().map(OpenApiIT::sort).toList();
        }
        return v;
    }
}
