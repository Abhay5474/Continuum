package io.continuum.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Tiny JSON-over-HTTP helper shared by provider adapters. */
public class HttpJson {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper;

    public HttpJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode post(String url, Object body, String[] headers, int timeoutSeconds) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new HttpStatusException(response.statusCode(), response.body());
        }
        return mapper.readTree(response.body());
    }

    /**
     * A GET, returning the status, headers and body whatever the status.
     *
     * <p>For reads where a non-2xx answer is information rather than failure —
     * a provider's model list, a probe whose 429 says "not on the free tier".
     * Listing endpoints are GETs; the discovery code used to send them as POSTs,
     * which every provider refused, and the refusal was swallowed.
     */
    public Result get(String url, String[] headers, int timeoutSeconds) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> r = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(r.statusCode(), r.headers().map(), r.body());
    }

    /** Like {@link #post}, but the non-2xx answer is returned rather than thrown. */
    public Result exchange(String url, Object body, String[] headers, int timeoutSeconds) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> r = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(r.statusCode(), r.headers().map(), r.body());
    }

    /** An HTTP answer, kept whole. */
    public record Result(int status, java.util.Map<String, java.util.List<String>> headers, String body) {

        public boolean ok() {
            return status / 100 == 2;
        }

        /** First value of a header, case-insensitively; null when absent. */
        public String header(String name) {
            if (headers == null) {
                return null;
            }
            for (var e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    /**
     * A non-2xx answer from {@link #post}. The message is unchanged ("HTTP 404:
     * body…"), so code that reads it keeps working; the status and body are
     * now also available without parsing the message back apart.
     */
    public static class HttpStatusException extends RuntimeException {
        private final int status;
        private final String body;

        public HttpStatusException(int status, String body) {
            super("HTTP " + status + ": " + truncate(body));
            this.status = status;
            this.body = body == null ? "" : (body.length() > 4000 ? body.substring(0, 4000) : body);
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) : s);
    }
}
