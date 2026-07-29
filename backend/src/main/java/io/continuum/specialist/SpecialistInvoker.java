package io.continuum.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.net.GuardedHttpSender;
import io.continuum.persistence.entity.SpecialistConnectionEntity;
import io.continuum.persistence.entity.SpecialistEntity;
import io.continuum.persistence.entity.TraceStepEntity;
import io.continuum.tool.Evidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls one specialist and normalises what comes back.
 *
 * <p>The sequence is: ask the provider adapter to shape the call, present the
 * developer's credential in whatever style that provider expects, send it
 * through the shared network guard, ask the adapter to parse the response, drop
 * findings below the configured confidence, and record a trace step describing
 * all of it.
 *
 * <p>Confidence filtering happens here rather than in the prompt because a
 * finding the developer said they do not trust should not reach the model at
 * all. Passing it along with a caveat invites the model to reason about it
 * anyway, which is how a 0.2 detection becomes a paragraph of advice.
 *
 * <p>An invocation never throws for a reason the caller cannot act on. A refused
 * target, a dead endpoint or an unparseable body all produce a {@link Result}
 * carrying the error, because the pipeline's next decision — proceed without the
 * specialist, or refuse — belongs to the confidence policy and not to the
 * transport.
 */
@Service
public class SpecialistInvoker {

    private static final Logger log = LoggerFactory.getLogger(SpecialistInvoker.class);

    private final SpecialistConnectionService connections;
    private final GuardedHttpSender sender;
    private final TraceRecorder traces;
    private final ObjectMapper mapper;

    public SpecialistInvoker(SpecialistConnectionService connections, GuardedHttpSender sender,
                             TraceRecorder traces, ObjectMapper mapper) {
        this.connections = connections;
        this.sender = sender;
        this.traces = traces;
        this.mapper = mapper;
    }

    /**
     * The outcome of one invocation.
     *
     * @param evidence   normalised, already filtered by confidence, strongest first
     * @param dropped    how many fell below the threshold — worth knowing, since
     *                   "nothing found" and "nothing confident enough" are
     *                   different situations
     * @param rawBody    what the provider actually returned, for the probe view
     * @param error      null on success
     */
    public record Result(boolean ok, List<Evidence> evidence, int dropped,
                         String rawBody, Integer httpStatus, long latencyMs, String error) {

        /**
         * The scored, labelled subset, as legacy findings.
         *
         * <p>Kept so every caller written against the Specialist layer keeps
         * compiling and behaving. Text, fields and rows are deliberately absent:
         * they cannot become a {@code Finding} without inventing a confidence.
         */
        public List<SpecialistProvider.Finding> findings() {
            List<SpecialistProvider.Finding> out = new ArrayList<>();
            for (Evidence e : evidence) {
                if (e.isFindingShaped()) {
                    out.add(new SpecialistProvider.Finding(
                            e.label(), e.confidence(), e.attributes().isEmpty() ? null : e.attributes()));
                }
            }
            return out;
        }

        public SpecialistProvider.Finding top() {
            List<SpecialistProvider.Finding> f = findings();
            return f.isEmpty() ? null : f.get(0);
        }

        /** Highest confidence among scored evidence; 0 when nothing is scored. */
        public double topConfidence() {
            double top = 0;
            for (Evidence e : evidence) {
                if (e.scored()) {
                    top = Math.max(top, e.confidence());
                }
            }
            return top;
        }

        /** Evidence that carries no confidence — text, fields, rows. */
        public long unscored() {
            return evidence.stream().filter(e -> !e.scored()).count();
        }
    }

    /**
     * Invokes a specialist.
     *
     * @param input   provider-shaped payload, e.g. {@code {"imageBase64": "..."}}
     * @param traceId optional; when present the call appears in the visible chain
     */
    public Result invoke(SpecialistEntity specialist, Map<String, Object> input, String traceId) {
        long start = System.nanoTime();
        SpecialistConnectionEntity connection;
        SpecialistProvider adapter;
        try {
            connection = connections.require(specialist.getDeveloperId(), specialist.getConnectionId());
            adapter = SpecialistProviders.byName(connection.getProvider());
        } catch (RuntimeException e) {
            return failed(specialist, traceId, start, "Specialist is not configured: " + e.getMessage());
        }

        Map<String, Object> payload = new LinkedHashMap<>(input == null ? Map.of() : input);
        payload.putIfAbsent("minConfidence", specialist.getMinConfidence());

        SpecialistProvider.Call call;
        try {
            call = adapter.buildCall(connection, specialist.getModelPath(), payload);
        } catch (RuntimeException e) {
            return failed(specialist, traceId, start, "Could not build the call: " + e.getMessage());
        }

        String url = call.url();
        Map<String, String> headers = new LinkedHashMap<>(call.headers());
        String secret = connections.secretFor(connection).orElse(null);

        // Present the credential the way this provider expects. Roboflow wants a
        // query parameter; most want a header or a bearer token.
        switch (connection.getAuthStyle()) {
            case BEARER -> {
                if (secret != null) {
                    headers.put("Authorization", "Bearer " + secret);
                }
            }
            case HEADER -> {
                if (secret != null && connection.getAuthParam() != null) {
                    headers.put(connection.getAuthParam(), secret);
                }
            }
            case QUERY -> {
                if (secret != null && connection.getAuthParam() != null) {
                    url += (url.contains("?") ? "&" : "?")
                            + URLEncoder.encode(connection.getAuthParam(), StandardCharsets.UTF_8)
                            + "=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
                }
            }
            case NONE -> { }
        }
        headers.putIfAbsent("Content-Type", "application/json");

        String body;
        try {
            body = call.body() instanceof String s ? s : mapper.writeValueAsString(call.body());
        } catch (Exception e) {
            return failed(specialist, traceId, start, "Could not serialise the request: " + e.getMessage());
        }

        GuardedHttpSender.Result res;
        try {
            res = sender.send(url, call.method(), headers, body, specialist.getTimeoutSeconds());
        } catch (GuardedHttpSender.NonRetryable e) {
            return failed(specialist, traceId, start, e.getMessage());
        } catch (Exception e) {
            return failed(specialist, traceId, start,
                    "Specialist call failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        long ms = (System.nanoTime() - start) / 1_000_000;

        if (res.status() >= 400) {
            // The provider's own message is the useful part; a developer
            // debugging a bad model path needs to see it verbatim.
            String detail = "Specialist returned " + res.status() + ": " + truncate(res.body());
            recordFailure(connection, detail);
            return trace(specialist, traceId,
                    new Result(false, List.of(), 0, res.body(), res.status(), ms, detail));
        }

        Object parsedBody;
        try {
            parsedBody = mapper.readValue(res.body(), Object.class);
        } catch (Exception e) {
            // Not JSON. Hand the adapter the raw text — some endpoints answer
            // with a bare string — and let it report nothing if it cannot cope.
            parsedBody = res.body();
        }

        List<Evidence> all;
        try {
            all = adapter.parseEvidence(parsedBody);
        } catch (RuntimeException e) {
            // An adapter is not allowed to throw, but a defect in one must not
            // become a failed customer request.
            log.warn("Adapter {} threw while parsing: {}", adapter.name(), e.getMessage());
            all = List.of();
        }

        // Every adapter's output passes through here, so the range check lives
        // here too. An adapter that returned 85 meaning 85% would otherwise
        // defeat the reporting threshold, the prose bands and the confidence
        // policy in one go.
        all = Evidence.normalise(all);

        List<Evidence> kept = new ArrayList<>();
        int dropped = 0;
        for (Evidence e : all) {
            // A confidence threshold is a statement about scores. Applying one
            // to unscored evidence would discard every OCR line and every
            // transcript, because none of them has a number to compare — the
            // filter would silently delete the output of whole classes of tool.
            if (!e.scored() || e.confidence() >= specialist.getMinConfidence()) {
                kept.add(e);
            } else {
                dropped++;
            }
        }
        connections.recordSuccess(connection);
        return trace(specialist, traceId,
                new Result(true, kept, dropped, res.body(), res.status(), ms, null));
    }

    private Result failed(SpecialistEntity s, String traceId, long start, String error) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        return trace(s, traceId, new Result(false, List.of(), 0, null, null, ms, error));
    }

    private void recordFailure(SpecialistConnectionEntity connection, String detail) {
        try {
            connections.recordFailure(connection, detail);
        } catch (Exception ignored) {
            // Bookkeeping only.
        }
    }

    /** Adds the invocation to the visible chain, then returns it unchanged. */
    private Result trace(SpecialistEntity s, String traceId, Result r) {
        if (traceId == null) {
            return r;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("specialist", s.getName());
        detail.put("model", s.getModelPath());
        // The full evidence, whatever shape it took. A trace that showed only
        // findings would show nothing at all for an OCR or transcription step.
        detail.put("evidence", r.evidence().stream().map(Evidence::describe).toList());
        detail.put("findings", r.findings().stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", f.label());
            m.put("confidence", f.confidence());
            return m;
        }).toList());
        detail.put("unscored", r.unscored());
        detail.put("belowThreshold", r.dropped());
        detail.put("minConfidence", s.getMinConfidence());
        if (r.error() != null) {
            detail.put("error", r.error());
        }
        traces.step(traceId, s.getDeveloperId(), TraceStepEntity.Kind.SPECIALIST,
                s.getName(), detail, r.ok() ? "OK" : "FAILED",
                r.findings().isEmpty() ? null : r.topConfidence(), 0, r.latencyMs());
        return r;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }
}
