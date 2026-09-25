package io.continuum.gateway.openai;

import io.continuum.gateway.GatewayDtos;
import io.continuum.gateway.GatewayService;
import io.continuum.persistence.entity.DeveloperEntity;
import io.continuum.developer.ApiKeyAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The OpenAI-compatible front door.
 *
 * <p>{@code /v1/chat/completions} was already advertised, but it answered in
 * Continuum's own response shape — no {@code choices}, no {@code usage} — so any
 * real SDK pointed here got a 200 and then a null-pointer on the first field it
 * read. This is the endpoint that makes "change the base URL" true: the OpenAI
 * request format in, the OpenAI response format out, including streaming, tool
 * calls and image parts.
 *
 * <h2>On streaming and this product</h2>
 *
 * <p>Continuum is a reliability layer, and several of the things it does need
 * the whole answer before they can act: the quality gate scores a finished
 * answer and may replace it, confidence samples the same question several times
 * and compares, the cascade judges a cheap answer before deciding to pay for an
 * expensive one. None of those can run on a token that has already left.
 *
 * <p>So there are two honest modes, and the response says which one you got:
 *
 * <ul>
 *   <li><b>passthrough</b> — nothing downstream needs the finished answer, so
 *       tokens are streamed as the provider produces them.</li>
 *   <li><b>buffered</b> — a post-generation feature is enabled, so the pipeline
 *       runs to completion and the final (possibly repaired) answer is then
 *       streamed. Time-to-first-token is the full generation time.</li>
 * </ul>
 *
 * <p>The alternative — streaming a draft and then contradicting it — is worse
 * than waiting, because the caller has already shown it to someone.
 */
@RestController
public class OpenAiCompatController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatController.class);

    /** Long enough for a slow model, short enough that a dead socket is reaped. */
    private static final long STREAM_TIMEOUT_MS = 10 * 60 * 1000L;

    /**
     * Roughly a word. Chunking by character would be technically finer and
     * practically worse — a client rendering per frame would repaint on every
     * letter, and the frame overhead would dominate the payload.
     */
    private static final int CHUNK_CHARS = 24;

    private final GatewayService gateway;
    private final OpenAiTranslator translator;
    private final StreamPolicy streamPolicy;

    /**
     * A dedicated pool. Streaming holds a thread for the life of the response,
     * and borrowing the container's request threads for that is how a gateway
     * stops accepting connections under perfectly ordinary load.
     */
    private final ExecutorService streams = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "openai-stream");
        t.setDaemon(true);
        return t;
    });

    public OpenAiCompatController(GatewayService gateway, OpenAiTranslator translator,
                                  StreamPolicy streamPolicy) {
        this.gateway = gateway;
        this.translator = translator;
        this.streamPolicy = streamPolicy;
    }

    @PostMapping(path = "/v1/chat/completions")
    public Object chatCompletions(@RequestBody OpenAiDtos.ChatCompletionRequest body,
                                  HttpServletRequest http) {
        DeveloperEntity developer =
                (DeveloperEntity) http.getAttribute(ApiKeyAuthenticationFilter.DEVELOPER_ATTRIBUTE);
        if (developer == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(OpenAiDtos.ErrorEnvelope.of("invalid_request_error",
                            "Authentication required.", "invalid_api_key"));
        }
        if (body == null || body.messages() == null || body.messages().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(OpenAiDtos.ErrorEnvelope.of("invalid_request_error",
                            "'messages' must not be empty.", null));
        }
        OpenAiDtos.ApiError invalid = validate(body);
        if (invalid != null) {
            return ResponseEntity.badRequest().body(new OpenAiDtos.ErrorEnvelope(invalid));
        }

        String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        GatewayDtos.ChatRequest req = translator.toGateway(body);

        if (!body.wantsStream()) {
            try {
                return ResponseEntity.ok(translator.toCompletion(id, gateway.chat(developer.getId(), req), null));
            } catch (RuntimeException e) {
                return errorResponse(e);
            }
        }
        return stream(id, developer.getId(), req, body);
    }

    private static final java.util.Set<String> ROLES =
            java.util.Set.of("system", "developer", "user", "assistant", "tool", "function");

    /**
     * The checks OpenAI itself makes before running anything. A request that
     * would be refused upstream used to be served here — a temperature of 9,
     * a negative token budget, a role nobody defines — so code that worked
     * against Continuum failed the day it was pointed at the real API, and a
     * typo in a role silently became a user turn.
     *
     * @return the first problem, in OpenAI's error shape, or null
     */
    static OpenAiDtos.ApiError validate(OpenAiDtos.ChatCompletionRequest body) {
        Integer max = body.effectiveMaxTokens();
        if (max != null && max < 1) {
            String param = body.maxCompletionTokens() != null ? "max_completion_tokens" : "max_tokens";
            return invalid("'" + param + "' must be at least 1, got " + max + ".", param);
        }
        if (body.temperature() != null && (body.temperature() < 0 || body.temperature() > 2)) {
            return invalid("'temperature' must be between 0 and 2, got " + body.temperature() + ".", "temperature");
        }
        if (body.topP() != null && (body.topP() < 0 || body.topP() > 1)) {
            return invalid("'top_p' must be between 0 and 1, got " + body.topP() + ".", "top_p");
        }
        if (body.n() != null && body.n() < 1) {
            return invalid("'n' must be at least 1.", "n");
        }
        for (int i = 0; i < body.messages().size(); i++) {
            OpenAiDtos.ChatMessage m = body.messages().get(i);
            String param = "messages[" + i + "]";
            if (m == null) {
                return invalid("Message " + i + " is null.", param);
            }
            if (m.role() == null || !ROLES.contains(m.role())) {
                return invalid("Message " + i + " has role " + (m.role() == null ? "missing" : "'" + m.role() + "'")
                        + "; expected one of system, developer, user, assistant, tool.", param + ".role");
            }
        }
        return null;
    }

    private static OpenAiDtos.ApiError invalid(String message, String param) {
        return new OpenAiDtos.ApiError(message, "invalid_request_error", null, param);
    }

    /**
     * The models a caller may name, in the OpenAI shape.
     *
     * <p>Several SDKs and nearly every playground call this on connect and
     * refuse to proceed without it, so its absence reads as "this endpoint is
     * not really OpenAI-compatible" long before anyone sends a completion.
     */
    @GetMapping("/v1/models")
    public Map<String, Object> models() {
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(modelEntry("auto"));
        for (String m : gateway.routableModelNames()) {
            data.add(modelEntry(m));
        }
        return Map.of("object", "list", "data", data);
    }

    private Map<String, Object> modelEntry(String id) {
        return Map.of("id", id, "object", "model", "created", 0, "owned_by", "continuum");
    }

    /* ---------------------------------------------------------------- *
     * Streaming
     * ---------------------------------------------------------------- */

    private SseEmitter stream(String id, String developerId,
                              GatewayDtos.ChatRequest req, OpenAiDtos.ChatCompletionRequest body) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        StreamPolicy.Mode mode = streamPolicy.modeFor(developerId);

        streams.execute(() -> {
            try {
                send(emitter, translator.openingChunk(id, req.model() == null ? "auto" : req.model()));

                GatewayDtos.ChatResponse r = gateway.chat(developerId, req);
                String model = r.model() != null ? r.model() : "auto";

                if (r.toolCalls() != null && !r.toolCalls().isEmpty()) {
                    int i = 0;
                    for (GatewayDtos.ToolCallRef call : r.toolCalls()) {
                        send(emitter, translator.toolCallChunk(id, model, i++, call));
                    }
                } else {
                    for (String piece : split(r.response())) {
                        send(emitter, translator.contentChunk(id, model, piece));
                    }
                }

                String finish = translator.finishReason(r, r.toolCalls() != null && !r.toolCalls().isEmpty());
                send(emitter, translator.finalChunk(id, model, finish,
                        body.wantsUsageInStream() ? translator.usage(r) : null,
                        new OpenAiDtos.ContinuumMeta(r.provider(), r.routingReason(), r.failovers(),
                                r.latency(), r.cost(), null, r.confidence(), r.lowConfidence(),
                                mode.wire())));
                send(emitter, "[DONE]");
                emitter.complete();
            } catch (Exception e) {
                // The stream has already started, so the status line is long
                // gone. An error frame is the only way left to tell the client
                // something went wrong — closing the socket silently would look
                // like a truncated answer, which is the one thing a caller must
                // never mistake this for.
                log.warn("Stream failed for developer {}: {}", developerId, e.getMessage());
                try {
                    send(emitter, translator.write(OpenAiDtos.ErrorEnvelope.of(
                            "server_error", String.valueOf(e.getMessage()), null)));
                    send(emitter, "[DONE]");
                } catch (Exception ignored) {
                    // The client is gone; nothing left to tell.
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, String data) throws IOException {
        emitter.send(SseEmitter.event().data(data, MediaType.TEXT_PLAIN));
    }

    /**
     * Splits an answer into frames on word boundaries.
     *
     * <p>Never mid-word: a client appending deltas renders them immediately, and
     * a word arriving in two frames visibly stutters.
     */
    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(text.length(), i + CHUNK_CHARS);
            if (end < text.length()) {
                int space = text.lastIndexOf(' ', end);
                if (space > i) {
                    end = space + 1;
                }
            }
            out.add(text.substring(i, end));
            i = end;
        }
        return out;
    }

    /** Maps the gateway's refusals onto the status codes an OpenAI client expects. */
    private ResponseEntity<OpenAiDtos.ErrorEnvelope> errorResponse(RuntimeException e) {
        String message = e.getMessage() == null ? "Upstream failure." : e.getMessage();
        if (e instanceof io.continuum.admission.CostAdmissionService.CostLimitedException c) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(c.decision().retryAfterSeconds()))
                    .body(OpenAiDtos.ErrorEnvelope.of("rate_limit_error", message, "cost_limited"));
        }
        if (e instanceof io.continuum.admission.AdmissionService.SheddedException) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1")
                    .body(OpenAiDtos.ErrorEnvelope.of("rate_limit_error", message, "capacity"));
        }
        if (e instanceof io.continuum.scheduling.SchedulerService.DeadlineUnreachableException) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(OpenAiDtos.ErrorEnvelope.of("invalid_request_error", message, "deadline_unreachable"));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(OpenAiDtos.ErrorEnvelope.of("api_error", message, "upstream_unavailable"));
    }
}
