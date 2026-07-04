package io.continuum.healing;

import io.continuum.common.Json;
import io.continuum.core.event.EventStore;
import io.continuum.core.event.Payloads;
import io.continuum.core.workflow.Commands;
import io.continuum.core.workflow.Workflow;
import io.continuum.core.workflow.WorkflowExecutor;
import io.continuum.core.workflow.WorkflowRegistry;
import io.continuum.persistence.entity.DivergenceResolutionEntity;
import io.continuum.persistence.entity.WorkflowEventEntity;
import io.continuum.persistence.entity.WorkflowHealingLogEntity;
import io.continuum.persistence.entity.WorkflowInstanceEntity;
import io.continuum.persistence.entity.WorkflowStatus;
import io.continuum.persistence.repository.DivergenceResolutionRepository;
import io.continuum.persistence.repository.WorkflowHealingLogRepository;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The Paradox Resolution Engine: compulsory auto-healing of determinism
 * divergence between deployed workflow code and the append-only event log.
 *
 * It is always active — there is no toggle. For every decision it opens a
 * {@link SequenceAlignmentSession} seeded with the instance's durable
 * resolution ledger; when the deployed code graph still matches history the
 * session is a pure pass-through (no rows written, no behavior change). When a
 * structural divergence is intercepted, the computed micro-patch mappings are
 * committed by {@link #commitResolutions} inside the engine's decision
 * transaction — the same transaction that records the decision's progress —
 * so ledger and history can never disagree.
 */
@Service
public class ParadoxResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ParadoxResolutionService.class);
    private static final int VERIFY_SCAN_LIMIT = 200;

    private final WorkflowHealingLogRepository healingLogs;
    private final DivergenceResolutionRepository resolutions;
    private final WorkflowInstanceRepository instances;
    private final WorkflowRegistry registry;
    private final WorkflowExecutor executor;
    private final EventStore eventStore;
    private final Json json;

    public ParadoxResolutionService(WorkflowHealingLogRepository healingLogs,
                                    DivergenceResolutionRepository resolutions,
                                    WorkflowInstanceRepository instances,
                                    WorkflowRegistry registry,
                                    WorkflowExecutor executor,
                                    EventStore eventStore,
                                    Json json) {
        this.healingLogs = healingLogs;
        this.resolutions = resolutions;
        this.instances = instances;
        this.registry = registry;
        this.executor = executor;
        this.eventStore = eventStore;
        this.json = json;
    }

    /**
     * Build the alignment session for one decision run: recorded activity types
     * and occupied command sequences from history, plus the instance's persisted
     * resolution ledger.
     */
    public SequenceAlignmentSession openSession(String workflowId, List<WorkflowEventEntity> history) {
        TreeMap<Long, String> activityTypes = new TreeMap<>();
        Set<Long> usedSeqs = new HashSet<>();
        for (WorkflowEventEntity e : history) {
            switch (e.getEventType()) {
                case ACTIVITY_SCHEDULED -> {
                    var p = json.read(e.getPayload(), Payloads.ActivityScheduled.class);
                    activityTypes.put(p.commandSeq(), p.activityType());
                    usedSeqs.add(p.commandSeq());
                }
                case SIDE_EFFECT_RECORDED -> {
                    var p = json.read(e.getPayload(), Payloads.SideEffectRecorded.class);
                    usedSeqs.add(p.commandSeq());
                }
                default -> { /* not part of the command sequence space */ }
            }
        }
        List<AlignmentMapping> persisted = new ArrayList<>();
        for (WorkflowHealingLogEntity row :
                healingLogs.findByWorkflowIdOrderByDivergenceSequenceNumberAsc(workflowId)) {
            persisted.add(json.read(row.getVirtualizedPayloadJson(), AlignmentMapping.class));
        }
        return new SequenceAlignmentSession(activityTypes, usedSeqs, persisted);
    }

    /**
     * Durably commit any micro-patch mappings the session produced. Called by
     * the engine inside its decision transaction (while the instance write lock
     * is held), so the ledger write is atomic with the decision's own events.
     */
    public void commitResolutions(SequenceAlignmentSession session, WorkflowInstanceEntity instance) {
        if (!session.diverged()) {
            return; // pristine path: zero writes, zero overhead
        }
        for (AlignmentMapping m : session.newMappings()) {
            healingLogs.save(new WorkflowHealingLogEntity(
                    instance.getWorkflowId(), m.codeSeq(), m.resolutionType().name(), json.write(m)));
            resolutions.save(new DivergenceResolutionEntity(
                    instance.getWorkflowId(), instance.getWorkflowType(), m.resolutionType().name(),
                    m.codeSeq(), m.historySeq(),
                    "activity=" + m.activityType() + " deltaAfter=" + m.deltaAfter()));
            log.warn("Paradox resolved for workflow {}: {} at code seq {} -> history seq {} ({})",
                    instance.getWorkflowId(), m.resolutionType(), m.codeSeq(), m.historySeq(),
                    m.activityType());
        }
    }

    /** Engine-wide metrics for the healing status API and dashboard. */
    @Transactional(readOnly = true)
    public Map<String, Object> status() {
        Map<String, Object> byType = new LinkedHashMap<>();
        long total = 0;
        for (HealingResolutionType t : HealingResolutionType.values()) {
            long c = healingLogs.countByResolutionType(t.name());
            byType.put(t.name(), c);
            total += c;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", "paradox-resolution");
        out.put("alwaysOn", true);
        out.put("totalResolutions", total);
        out.put("healedWorkflows", healingLogs.countHealedWorkflows());
        out.put("byResolutionType", byType);
        out.put("recentResolutions", recentTimeline());
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentTimeline() {
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (DivergenceResolutionEntity r : resolutions.findTop100ByOrderByResolvedAtDesc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workflowId", r.getWorkflowId());
            row.put("workflowType", r.getWorkflowType());
            row.put("resolutionType", r.getResolutionType());
            row.put("codeSequence", r.getCodeSequence());
            row.put("historySequence", r.getHistorySequence());
            row.put("detail", r.getDetail());
            row.put("resolvedAt", r.getResolvedAt());
            timeline.add(row);
        }
        return timeline;
    }

    /** The full structural patch ledger for one healed workflow instance. */
    @Transactional(readOnly = true)
    public Map<String, Object> workflowLedger(String workflowId) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (WorkflowHealingLogEntity row :
                healingLogs.findByWorkflowIdOrderByDivergenceSequenceNumberAsc(workflowId)) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("divergenceSequenceNumber", row.getDivergenceSequenceNumber());
            e.put("resolutionType", row.getResolutionType());
            e.put("virtualizedPayload", json.read(row.getVirtualizedPayloadJson(), AlignmentMapping.class));
            e.put("createdAt", row.getCreatedAt());
            entries.add(e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflowId", workflowId);
        out.put("healed", !entries.isEmpty());
        out.put("resolutionCount", entries.size());
        out.put("ledger", entries);
        return out;
    }

    /**
     * Explicit code-to-history validation scan (dry run). Replays the deployed
     * code against history with a fresh session but never persists anything —
     * divergences that would be healed on the next real decision are reported.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> verify(String workflowId) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (workflowId != null && !workflowId.isBlank()) {
            instances.findById(workflowId)
                    .ifPresentOrElse(i -> results.add(verifyInstance(i)),
                            () -> results.add(Map.of("workflowId", workflowId, "error", "unknown workflow")));
        } else {
            for (WorkflowInstanceEntity i : instances
                    .findByStatusOrderByCreatedAtDesc(WorkflowStatus.RUNNING, PageRequest.of(0, VERIFY_SCAN_LIMIT))) {
                results.add(verifyInstance(i));
            }
        }
        long diverged = results.stream().filter(r -> Boolean.TRUE.equals(r.get("diverged"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scanned", results.size());
        out.put("divergedInstances", diverged);
        out.put("results", results);
        return out;
    }

    private Map<String, Object> verifyInstance(WorkflowInstanceEntity instance) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflowId", instance.getWorkflowId());
        out.put("workflowType", instance.getWorkflowType());
        out.put("status", instance.getStatus().name());
        if (!registry.contains(instance.getWorkflowType())) {
            out.put("error", "workflow type not registered in this deployment");
            return out;
        }
        List<WorkflowEventEntity> history = eventStore.history(instance.getWorkflowId());
        ReplayFold fold = fold(history);
        SequenceAlignmentSession session = openSession(instance.getWorkflowId(), history);
        Workflow workflow = registry.get(instance.getWorkflowType());
        try {
            Commands.Decision decision = executor.runDecision(workflow, instance.getWorkflowId(),
                    instance.getInput(), fold.completed, fold.failed, fold.sideEffects, fold.scheduled,
                    session);
            out.put("decisionKind", decision.kind().name());
        } catch (RuntimeException e) {
            out.put("error", "replay raised: " + e.getMessage());
        }
        out.put("diverged", session.diverged());
        out.put("pendingResolutions", session.newMappings());
        return out;
    }

    private ReplayFold fold(List<WorkflowEventEntity> history) {
        ReplayFold st = new ReplayFold();
        for (WorkflowEventEntity e : history) {
            switch (e.getEventType()) {
                case ACTIVITY_SCHEDULED -> {
                    var p = json.read(e.getPayload(), Payloads.ActivityScheduled.class);
                    st.scheduled.add(p.commandSeq());
                }
                case ACTIVITY_COMPLETED -> {
                    var p = json.read(e.getPayload(), Payloads.ActivityCompleted.class);
                    st.completed.put(p.commandSeq(), p.result());
                    st.scheduled.remove(p.commandSeq());
                }
                case ACTIVITY_FAILED -> {
                    var p = json.read(e.getPayload(), Payloads.ActivityFailed.class);
                    if (p.terminal()) {
                        st.failed.put(p.commandSeq(), p.error());
                        st.scheduled.remove(p.commandSeq());
                    }
                }
                case SIDE_EFFECT_RECORDED -> {
                    var p = json.read(e.getPayload(), Payloads.SideEffectRecorded.class);
                    st.sideEffects.put(p.commandSeq(), p.value());
                }
                default -> { /* not replay-relevant */ }
            }
        }
        return st;
    }

    private static final class ReplayFold {
        final Map<Long, String> completed = new HashMap<>();
        final Map<Long, String> failed = new HashMap<>();
        final Map<Long, String> sideEffects = new HashMap<>();
        final Set<Long> scheduled = new HashSet<>();
    }
}
