package io.continuum.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.api.WorkflowQueryService;
import io.continuum.api.dto.Dtos;
import io.continuum.declarative.CancellationRollback;
import io.continuum.declarative.DefinitionService;
import io.continuum.developer.ApiKeyAuthenticationFilter;
import io.continuum.loops.LoopGuardService;
import io.continuum.memory.MemoryService;
import io.continuum.memory.MemoryTier;
import io.continuum.persistence.entity.DeveloperEntity;
import io.continuum.portal.RequestScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The engine's features, for programs holding an API key.
 *
 * <p>An application could call the models with its key but nothing else: a
 * durable workflow, long-term memory and the loop detector all answered only
 * to a signed-in person in the console — though the loop detector exists
 * precisely for agent frameworks Continuum does not run, and a workflow is
 * started by the application that needs it, not by someone clicking. These
 * are the same services, scoped to the key's account exactly as the console
 * scopes them, so what a program does here is what the console shows.
 *
 * <p>Deliberately not here: anything that changes the account — settings,
 * keys, webhooks, definitions. A leaked key can run the workflows its owner
 * published; it cannot rewrite them.
 */
@RestController
public class ProgramApiController {

    private final DefinitionService definitions;
    private final WorkflowQueryService query;
    private final io.continuum.core.engine.WorkflowEngine engine;
    private final CancellationRollback rollback;
    private final io.continuum.saga.SagaService saga;
    private final MemoryService memory;
    private final LoopGuardService loops;
    private final io.continuum.autopilot.AutopilotService autopilot;
    private final ObjectMapper mapper;

    public ProgramApiController(DefinitionService definitions, WorkflowQueryService query,
                                io.continuum.core.engine.WorkflowEngine engine, CancellationRollback rollback,
                                io.continuum.saga.SagaService saga, MemoryService memory, LoopGuardService loops,
                                io.continuum.autopilot.AutopilotService autopilot, ObjectMapper mapper) {
        this.definitions = definitions;
        this.query = query;
        this.engine = engine;
        this.rollback = rollback;
        this.saga = saga;
        this.memory = memory;
        this.loops = loops;
        this.autopilot = autopilot;
        this.mapper = mapper;
    }

    private static String dev(HttpServletRequest http) {
        DeveloperEntity d = (DeveloperEntity) http.getAttribute(ApiKeyAuthenticationFilter.DEVELOPER_ATTRIBUTE);
        if (d == null) {
            // The filter refuses first; this is the belt to its braces.
            throw new RequestScope.ForbiddenException("An API key is required.");
        }
        return d.getId();
    }

    /* ------------------------------------------------------------------ *
     * Workflows
     * ------------------------------------------------------------------ */

    /**
     * Starts a durable run of a published definition. Pass {@code workflowId}
     * to make the start idempotent: a retried request with the same id joins
     * the run it already started instead of starting a second one.
     */
    @PostMapping("/api/gateway/workflows/{name}/run")
    public Map<String, Object> run(@PathVariable String name, @RequestParam(required = false) Integer version,
                                   @RequestBody(required = false) RunRequest body, HttpServletRequest http) {
        return definitions.run(dev(http), name, version,
                body == null || body.input() == null ? Map.of() : body.input(),
                body == null ? null : body.workflowId());
    }

    /**
     * Where a run is: its status, each step with its attempts and outcome, the
     * result or the error, and the rollback when one ran. 404 for a run that
     * belongs to another account, exactly as for one that does not exist.
     */
    @GetMapping("/api/gateway/workflows/{id}")
    public Map<String, Object> status(@PathVariable String id, HttpServletRequest http) {
        String developerId = dev(http);
        requireOwned(developerId, id);
        Dtos.WorkflowDetail d = query.detail(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflowId", id);
        String status = publicStatus(d.summary());
        out.put("status", status);
        out.put("createdAt", d.summary().createdAt());
        out.put("updatedAt", d.summary().updatedAt());
        List<Map<String, Object>> steps = steps(d.events());
        if (!"RUNNING".equals(status) && !"STUCK".equals(status)) {
            // The run is over: a step still waiting or in flight will never finish.
            String never = "CANCELLED".equals(status) ? "cancelled" : "abandoned";
            for (Map<String, Object> row : steps) {
                if (List.of("scheduled", "running", "retrying").contains(row.get("state"))) {
                    row.put("state", never);
                }
            }
        }
        out.put("steps", steps);
        if (d.result() != null) {
            out.put("result", d.result());
        }
        if (d.error() != null) {
            out.put("error", d.error());
        }
        saga.rollbackFor(developerId, id).ifPresent(r -> out.put("rollback", r));
        return out;
    }

    /**
     * Stops a run. With saga rollback on, the steps it completed are undone by
     * a rollback run, whose id comes back so it can be followed.
     */
    @PostMapping("/api/gateway/workflows/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, @RequestBody(required = false) Map<String, String> body,
                                      HttpServletRequest http) {
        requireOwned(dev(http), id);
        String reason = body == null ? null : body.get("reason");
        if (reason != null && reason.length() > 500) {
            reason = reason.substring(0, 500);
        }
        String before = query.find(id).map(WorkflowQueryService.Existing::status).orElse(null);
        engine.cancel(id, reason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflowId", id);
        // The engine records a cancelled run as failed; callers are told it was cancelled.
        out.put("status", publicStatus(query.detail(id).summary()));
        if ("RUNNING".equals(before)) {
            rollback.afterCancel(id, reason == null ? "cancelled" : reason)
                    .ifPresent(r -> out.put("rollbackWorkflowId", r));
        }
        return out;
    }

    private static String publicStatus(Dtos.WorkflowSummary summary) {
        return summary.cancelled() ? "CANCELLED" : summary.stuck() ? "STUCK" : summary.status();
    }

    private void requireOwned(String developerId, String workflowId) {
        // Another app's run is answered exactly like another account's: an app
        // must not follow, let alone cancel, a run it did not start.
        if (!query.visibleTo(developerId, workflowId)) {
            throw new RequestScope.NotFoundException("No workflow " + workflowId + " in this account.");
        }
    }

    /**
     * One row per step, from the run's history: the scheduling event names the
     * step (its idempotency key ends in the step id, and a compensation's in
     * {@code compensate:<step>}); the later events say how it went.
     */
    List<Map<String, Object>> steps(List<Dtos.EventView> events) {
        Map<Long, Map<String, Object>> bySeq = new TreeMap<>();
        for (Dtos.EventView e : events) {
            Map<?, ?> p = asMap(e.payload());
            if (p == null || !(p.get("commandSeq") instanceof Number n)) {
                continue;
            }
            long seq = n.longValue();
            switch (e.eventType()) {
                case "ACTIVITY_SCHEDULED" -> {
                    Map<?, ?> input = asMap(p.get("input"));
                    String step = stepName(input);
                    if (step == null) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    boolean undo = step.startsWith("undo ");
                    row.put("step", undo ? step.substring(5) : step);
                    row.put("kind", undo ? "compensation" : "step");
                    row.put("state", "scheduled");
                    row.put("attempts", 0);
                    if (input != null && input.get("url") != null) {
                        row.put("url", input.get("url"));
                    }
                    if (input != null && input.get("seconds") instanceof Number s) {
                        row.put("waitSeconds", s);
                    }
                    bySeq.put(seq, row);
                }
                case "ACTIVITY_STARTED" -> update(bySeq, seq, row -> {
                    row.put("state", "running");
                    if (p.get("attempt") instanceof Number a) {
                        row.put("attempts", a.intValue());
                    }
                });
                case "ACTIVITY_COMPLETED" -> update(bySeq, seq, row -> {
                    row.put("state", "completed");
                    Map<?, ?> result = asMap(p.get("result"));
                    if (result != null && result.containsKey("status")) {
                        row.put("httpStatus", result.get("status"));
                    }
                    row.remove("error");
                });
                case "ACTIVITY_FAILED" -> update(bySeq, seq, row -> {
                    boolean terminal = Boolean.TRUE.equals(p.get("terminal"));
                    row.put("state", terminal ? "failed" : "retrying");
                    row.put("error", truncate(String.valueOf(p.get("error"))));
                    if (p.get("attempt") instanceof Number a) {
                        row.put("attempts", a.intValue());
                    }
                });
                case "RETRY_SCHEDULED" -> update(bySeq, seq, row -> row.put("state", "retrying"));
                default -> {
                }
            }
        }
        return new ArrayList<>(bySeq.values());
    }

    private static void update(Map<Long, Map<String, Object>> bySeq, long seq,
                               java.util.function.Consumer<Map<String, Object>> change) {
        Map<String, Object> row = bySeq.get(seq);
        if (row != null) {
            change.accept(row);
        }
    }

    /** {@code wf:step} → step; {@code wf:compensate:step} → "undo step"; a wait names itself. */
    static String stepName(Map<?, ?> input) {
        if (input == null) {
            return null;
        }
        Object key = input.get("idempotencyKey");
        if (key instanceof String k && !k.isBlank()) {
            String step = k.contains(":") ? k.substring(k.lastIndexOf(':') + 1) : k;
            return k.contains(":compensate:") ? "undo " + step : step;
        }
        Object stepId = input.get("stepId");
        return stepId instanceof String s && !s.isBlank() ? s : null;
    }

    /** Payload fields arrive as JSON trees, maps or JSON text, depending on the event. */
    private Map<?, ?> asMap(Object v) {
        if (v instanceof Map<?, ?> m) {
            return m;
        }
        if (v instanceof com.fasterxml.jackson.databind.JsonNode node) {
            if (node.isObject()) {
                return mapper.convertValue(node, Map.class);
            }
            return node.isTextual() ? asMap(node.asText()) : null;
        }
        if (v instanceof String s && s.startsWith("{")) {
            try {
                return mapper.readValue(s, Map.class);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }

    /* ------------------------------------------------------------------ *
     * Memory
     * ------------------------------------------------------------------ */

    /** The same scoping the console uses, so a program's memories show up there. */
    private static String scoped(String developerId, String scope) {
        String safe = scope == null || scope.isBlank() ? "default" : scope.strip();
        if (safe.length() > 120) {
            throw new IllegalArgumentException("scope is limited to 120 characters");
        }
        return developerId + "::" + safe;
    }

    @PostMapping("/api/gateway/memory/store")
    public Map<String, Object> remember(@RequestBody MemoryStore body, HttpServletRequest http) {
        if (body == null || body.content() == null || body.content().isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        if (body.content().length() > 20_000) {
            throw new IllegalArgumentException("content is limited to 20000 characters");
        }
        MemoryTier tier;
        try {
            tier = body.tier() == null || body.tier().isBlank() ? MemoryTier.EPISODIC
                    : MemoryTier.valueOf(body.tier().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("tier must be one of " + java.util.Arrays.toString(MemoryTier.values()));
        }
        double salience = body.salience() == null ? 0.5 : Math.max(0, Math.min(1, body.salience()));
        var saved = memory.storeOrReinforce(scoped(dev(http), body.scope()), tier, body.content(), salience);
        return Map.of("id", saved.entry().getId(), "tier", saved.entry().getTier().name(),
                "reinforced", saved.reinforced());
    }

    @PostMapping("/api/gateway/memory/retrieve")
    public List<MemoryService.RetrievedMemory> recall(@RequestBody MemoryQuery body, HttpServletRequest http) {
        int k = body == null || body.topK() == null ? 5 : Math.max(1, Math.min(50, body.topK()));
        return memory.retrieve(scoped(dev(http), body == null ? null : body.scope()),
                body == null || body.query() == null ? "" : body.query(), k);
    }

    /* ------------------------------------------------------------------ *
     * Loop detection and feedback
     * ------------------------------------------------------------------ */

    /**
     * Asks whether an agent is going round in circles. In HALT mode a loop is
     * a 409 carrying the verdict, so a client that only checks the status code
     * still stops.
     */
    @PostMapping("/api/gateway/loops/inspect")
    public ResponseEntity<Map<String, Object>> inspect(@RequestBody LoopInspect body, HttpServletRequest http) {
        if (body == null || body.steps() == null) {
            throw new IllegalArgumentException("steps is required");
        }
        if (body.steps().size() > 500) {
            throw new IllegalArgumentException("steps is limited to 500 entries");
        }
        try {
            var v = loops.check(dev(http), body.workflowId(), body.steps(), body.progress());
            return ResponseEntity.ok(v == null ? Map.of("enabled", false, "looping", false) : v.describe());
        } catch (LoopGuardService.LoopHaltedException e) {
            Map<String, Object> out = new LinkedHashMap<>(e.verdict().describe());
            out.put("halted", true);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(out);
        }
    }

    /**
     * A rating of an answer from the application's own users, which Autopilot
     * weighs when it tunes the routing policy. {@code requestRef} is the
     * answer's request id ({@code req_…}); {@code score} runs from -1 (bad) to
     * 1 (good).
     */
    @PostMapping("/api/gateway/feedback")
    public Map<String, Object> feedback(@RequestBody Feedback body, HttpServletRequest http) {
        if (body == null || body.requestRef() == null || body.requestRef().isBlank() || body.score() == null) {
            throw new IllegalArgumentException("requestRef and score are required");
        }
        // -1 (bad) to 1 (good), as the console's own feedback form sends it.
        double score = Math.max(-1, Math.min(1, body.score()));
        String comment = body.comment() == null ? null
                : body.comment().length() > 1000 ? body.comment().substring(0, 1000) : body.comment();
        autopilot.addFeedback(dev(http), body.requestRef().strip(), score, comment);
        return Map.of("recorded", true);
    }

    public record RunRequest(Map<String, Object> input, String workflowId) {
    }

    public record MemoryStore(String scope, String tier, String content, Double salience) {
    }

    public record MemoryQuery(String scope, String query, Integer topK) {
    }

    public record LoopInspect(String workflowId, List<String> steps, List<Boolean> progress) {
    }

    public record Feedback(String requestRef, Double score, String comment) {
    }
}
