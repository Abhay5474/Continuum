package io.continuum.dag;

import io.continuum.common.Json;
import io.continuum.core.engine.WorkflowEngine;
import io.continuum.core.event.EventType;
import io.continuum.core.event.Payloads;
import io.continuum.dag.DagModels.Claim;
import io.continuum.dag.DagModels.ClaimScore;
import io.continuum.dag.DagModels.DagResult;
import io.continuum.dag.DagModels.GraphEdge;
import io.continuum.dag.DagModels.SolverOutput;
import io.continuum.dag.DagModels.VerifierOutput;
import io.continuum.gateway.GatewayDtos;
import io.continuum.persistence.entity.*;
import io.continuum.persistence.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V6 orchestration: gates the feature per developer, runs a Consensus DAG
 * workflow to completion on the EXISTING durable engine (event sourcing +
 * Postgres task queues — the service only polls the instance projection), and
 * packages the verified answer into the exact same {@code ChatResponse} shape
 * the legacy gateway path returns, so external clients never know a DAG ran.
 *
 * A completed run is also projected into {@code dag_runs/nodes/edges} for the
 * Execution Command Center trace UI; the event log stays the source of truth.
 */
@Service
public class ConsensusDagService {

    private static final Logger log = LoggerFactory.getLogger(ConsensusDagService.class);

    private final DeveloperAuthRepository devAuth;
    private final WorkflowEngine engine;
    private final WorkflowInstanceRepository instances;
    private final WorkflowEventRepository events;
    private final DagRunRepository runs;
    private final DagNodeRepository nodes;
    private final DagEdgeRepository edges;
    private final Json json;
    private final long timeoutMs;

    public ConsensusDagService(DeveloperAuthRepository devAuth, WorkflowEngine engine,
                               WorkflowInstanceRepository instances, WorkflowEventRepository events,
                               DagRunRepository runs, DagNodeRepository nodes, DagEdgeRepository edges,
                               Json json,
                               @Value("${continuum.dag.timeout-ms:90000}") long timeoutMs) {
        this.devAuth = devAuth;
        this.engine = engine;
        this.instances = instances;
        this.events = events;
        this.runs = runs;
        this.nodes = nodes;
        this.edges = edges;
        this.json = json;
        this.timeoutMs = timeoutMs;
    }

    /** The single V6 gate: FALSE (the default) means the legacy path runs untouched. */
    public boolean enabledFor(String developerId) {
        if (developerId == null) {
            return false;
        }
        return devAuth.findById(developerId)
                .map(DeveloperAuthEntity::isV6DagEnabled).orElse(false);
    }

    @Transactional
    public boolean setEnabled(String developerId, boolean enabled) {
        DeveloperAuthEntity auth = devAuth.findById(developerId)
                .orElseThrow(() -> new IllegalStateException("No portal account for developer"));
        auth.setV6DagEnabled(enabled);
        devAuth.save(auth);
        log.info("V6 Consensus DAG Engine {} for developer {}", enabled ? "ENABLED" : "DISABLED", developerId);
        return enabled;
    }

    /**
     * Run one gateway request through the Consensus DAG and wait for the
     * verdict. The wait is a plain poll of the instance projection — all
     * actual execution happens on the durable engine's worker pool.
     */
    public GatewayDtos.ChatResponse run(String developerId, GatewayDtos.ChatRequest req) {
        long started = System.nanoTime();
        String prompt = lastUserMessage(req);
        String workflowId = "dag-" + UUID.randomUUID();
        engine.startWorkflow(ConsensusDagWorkflow.TYPE,
                json.write(new ConsensusDagWorkflow.Input(developerId, prompt)), workflowId);
        runs.save(new DagRunEntity(workflowId, developerId, prompt, "RUNNING"));

        WorkflowInstanceEntity instance = awaitCompletion(workflowId);
        if (instance == null || instance.getStatus() != WorkflowStatus.COMPLETED) {
            markFailed(workflowId, instance);
            throw new IllegalStateException("Consensus DAG did not complete: "
                    + (instance == null ? "timeout" : instance.getStatus()));
        }

        DagResult result = json.read(instance.getResult(), DagResult.class);
        try {
            project(workflowId, result);
        } catch (Exception e) {
            log.warn("DAG trace projection failed for {} (run still succeeded): {}",
                    workflowId, e.getMessage());
        }

        long totalMs = (System.nanoTime() - started) / 1_000_000;
        String reason = String.format(
                "V6 consensus DAG %s: %d claims, %d verifiers, confidence %.1f%% (%s uncertainty)",
                workflowId, result.plan().claims().size(), result.verifiers().size(),
                result.finalConfidence() * 100, result.uncertainty());
        return new GatewayDtos.ChatResponse(result.answer(), "continuum", "consensus-dag-v6",
                totalMs, result.totalTokens(), result.totalCostUsd(), 0, reason);
    }

    private WorkflowInstanceEntity awaitCompletion(String workflowId) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            WorkflowInstanceEntity i = instances.findById(workflowId).orElse(null);
            if (i != null && i.getStatus() != WorkflowStatus.RUNNING) {
                return i;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return instances.findById(workflowId).orElse(null);
    }

    private void markFailed(String workflowId, WorkflowInstanceEntity instance) {
        runs.findByWorkflowId(workflowId).ifPresent(r -> {
            r.setStatus(instance == null || instance.getStatus() == WorkflowStatus.RUNNING
                    ? "TIMEOUT" : instance.getStatus().name());
            r.setCompletedAt(Instant.now());
            runs.save(r);
        });
    }

    // ---- trace projection (derived view; workflow_events remains the truth) ----

    @Transactional
    public void project(String workflowId, DagResult result) {
        Map<Long, Timing> timings = timingsFor(workflowId);
        nodes.deleteByWorkflowId(workflowId);
        edges.deleteByWorkflowId(workflowId);

        List<DagNodeEntity> nodeRows = new ArrayList<>();
        List<DagEdgeEntity> edgeRows = new ArrayList<>();

        Timing plannerT = timingByType(timings, "dag.plan", null, null);
        nodeRows.add(new DagNodeEntity(workflowId, "planner", "PLANNER", null,
                "Task decomposition → " + result.plan().claims().size() + " claims", "DONE", null,
                json.write(result.plan()), t(plannerT, true), t(plannerT, false)));

        Map<Integer, ClaimScore> scores = new HashMap<>();
        result.aggregation().scores().forEach(s -> scores.put(s.claimId(), s));

        for (Claim c : result.plan().claims()) {
            SolverOutput solved = result.solvers().stream()
                    .filter(s -> s.claimId() == c.id()).findFirst().orElse(null);
            String solverKey = "solver-" + c.id();
            Timing st = timingByType(timings, "dag.solve", c.id(), null);
            ClaimScore score = scores.get(c.id());
            nodeRows.add(new DagNodeEntity(workflowId, solverKey, "SOLVER", c.id(),
                    c.statement(), score != null && !score.survived() ? "FAIL" : "PASS",
                    solved == null ? null : solved.confidence(),
                    json.write(solved), t(st, true), t(st, false)));
            edgeRows.add(new DagEdgeEntity(workflowId, "planner", solverKey, "FLOW", 0.5));

            for (String check : c.checks()) {
                VerifierOutput v = result.verifiers().stream()
                        .filter(x -> x.claimId() == c.id() && check.equals(x.check()))
                        .findFirst().orElse(null);
                String verifierKey = "verify-" + c.id() + "-" + check.toLowerCase();
                Timing vt = timingByType(timings, "dag.verify", c.id(), check);
                String status = v == null ? "UNCERTAIN"
                        : v.validity() >= 0.65 ? "PASS" : v.validity() <= 0.4 ? "FAIL" : "UNCERTAIN";
                nodeRows.add(new DagNodeEntity(workflowId, verifierKey, "VERIFIER", c.id(),
                        check + " · claim " + c.id(), status,
                        v == null ? null : v.validity(), json.write(v), t(vt, true), t(vt, false)));
                edgeRows.add(new DagEdgeEntity(workflowId, solverKey, verifierKey, "FLOW", 0.5));
                edgeRows.add(new DagEdgeEntity(workflowId, verifierKey, "aggregator", "FLOW",
                        v == null ? 0.3 : v.validity()));
            }
        }

        // Contradiction / support / dependency edges between solver nodes,
        // with an explicit conflict node per contradiction.
        for (GraphEdge e : result.edges()) {
            String from = "solver-" + e.fromClaim();
            String to = "solver-" + e.toClaim();
            if (GraphEdge.CONTRADICTS.equals(e.type())) {
                String conflictKey = "conflict-" + e.fromClaim() + "-" + e.toClaim();
                boolean resolved = !result.aggregation().spine().isEmpty();
                nodeRows.add(new DagNodeEntity(workflowId, conflictKey, "CONFLICT", null,
                        "Claims " + e.fromClaim() + " ↔ " + e.toClaim() + " contradict",
                        resolved ? "PASS" : "FAIL", e.weight(),
                        json.write(e), null, null));
                edgeRows.add(new DagEdgeEntity(workflowId, from, conflictKey, "CONTRADICTS", e.weight()));
                edgeRows.add(new DagEdgeEntity(workflowId, to, conflictKey, "CONTRADICTS", e.weight()));
                edgeRows.add(new DagEdgeEntity(workflowId, conflictKey, "aggregator", "FLOW", e.weight()));
            } else {
                edgeRows.add(new DagEdgeEntity(workflowId, from, to, e.type(), e.weight()));
            }
        }

        nodeRows.add(new DagNodeEntity(workflowId, "aggregator", "AGGREGATOR", null,
                String.format("Bayesian resolution → %.1f%% (%s)",
                        result.finalConfidence() * 100, result.uncertainty()),
                result.aggregation().spine().isEmpty() ? "FAIL" : "PASS",
                result.finalConfidence(), json.write(result.aggregation()), null, null));
        Timing synthT = timingByType(timings, "dag.synthesize", null, null);
        nodeRows.add(new DagNodeEntity(workflowId, "synthesis", "SYNTHESIS", null,
                "Human-readable narration", "DONE", null,
                json.write(result.answer()), t(synthT, true), t(synthT, false)));
        edgeRows.add(new DagEdgeEntity(workflowId, "aggregator", "synthesis", "FLOW",
                result.finalConfidence()));

        nodes.saveAll(nodeRows);
        edges.saveAll(edgeRows);

        runs.findByWorkflowId(workflowId).ifPresent(r -> {
            r.setStatus("COMPLETED");
            r.setFinalConfidence(result.finalConfidence());
            r.setUncertainty(result.uncertainty());
            r.setVerdict(result.answer());
            r.setRiskFlagsJson(json.write(result.riskFlags()));
            r.setClaimCount(result.plan().claims().size());
            r.setNodeCount(nodeRows.size());
            r.setCompletedAt(Instant.now());
            runs.save(r);
        });
    }

    // ---- read APIs for the trace UI ----

    @Transactional(readOnly = true)
    public List<DagRunEntity> recentRuns(String developerId) {
        return developerId == null || developerId.isBlank()
                ? runs.findTop50ByOrderByCreatedAtDesc()
                : runs.findTop50ByDeveloperIdOrderByCreatedAtDesc(developerId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> trace(String workflowId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", runs.findByWorkflowId(workflowId).orElse(null));
        out.put("nodes", nodes.findByWorkflowIdOrderByIdAsc(workflowId));
        out.put("edges", edges.findByWorkflowId(workflowId));
        return out;
    }

    // ---- event-log timing extraction ----

    private record Timing(String type, Integer claimId, String check, Instant scheduled, Instant completed) {
    }

    private Map<Long, Timing> timingsFor(String workflowId) {
        Map<Long, Timing> out = new LinkedHashMap<>();
        for (WorkflowEventEntity e : events.findByWorkflowIdOrderBySequenceNumberAsc(workflowId)) {
            try {
                if (e.getEventType() == EventType.ACTIVITY_SCHEDULED) {
                    var p = json.read(e.getPayload(), Payloads.ActivityScheduled.class);
                    Integer claimId = null;
                    String check = null;
                    if (p.input() != null && p.input().contains("claimId")) {
                        var node = json.mapper().readTree(p.input());
                        claimId = node.path("claimId").isMissingNode() ? null : node.path("claimId").asInt();
                        check = node.path("check").isMissingNode() || node.path("check").isNull()
                                ? null : node.path("check").asText(null);
                    }
                    out.put(p.commandSeq(), new Timing(p.activityType(), claimId, check,
                            e.getCreatedAt(), null));
                } else if (e.getEventType() == EventType.ACTIVITY_COMPLETED) {
                    var p = json.read(e.getPayload(), Payloads.ActivityCompleted.class);
                    Timing prev = out.get(p.commandSeq());
                    if (prev != null) {
                        out.put(p.commandSeq(), new Timing(prev.type(), prev.claimId(), prev.check(),
                                prev.scheduled(), e.getCreatedAt()));
                    }
                }
            } catch (Exception ignored) {
                // timing extraction is best-effort; the projection tolerates gaps
            }
        }
        return out;
    }

    private Timing timingByType(Map<Long, Timing> timings, String type, Integer claimId, String check) {
        return timings.values().stream()
                .filter(x -> type.equals(x.type()))
                .filter(x -> claimId == null || claimId.equals(x.claimId()))
                .filter(x -> check == null || check.equals(x.check()))
                .findFirst().orElse(null);
    }

    private static Instant t(Timing timing, boolean start) {
        return timing == null ? null : (start ? timing.scheduled() : timing.completed());
    }

    private static String lastUserMessage(GatewayDtos.ChatRequest req) {
        if (req.messages() != null) {
            for (int i = req.messages().size() - 1; i >= 0; i--) {
                var m = req.messages().get(i);
                if ("user".equalsIgnoreCase(m.role())) {
                    return m.content();
                }
            }
        }
        return "";
    }
}
