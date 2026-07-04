package io.continuum.core.engine;

import io.continuum.common.Json;
import io.continuum.core.event.EventStore;
import io.continuum.core.event.EventType;
import io.continuum.core.event.Payloads;
import io.continuum.core.workflow.Commands;
import io.continuum.core.workflow.Workflow;
import io.continuum.core.workflow.WorkflowExecutor;
import io.continuum.core.workflow.WorkflowRegistry;
import io.continuum.healing.ParadoxResolutionService;
import io.continuum.healing.SequenceAlignmentSession;
import io.continuum.persistence.entity.*;
import io.continuum.persistence.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Drives workflow executions forward.
 *
 * Starting a workflow records {@code WORKFLOW_STARTED} and enqueues a workflow
 * task. Each workflow task triggers a decision: replay the deterministic
 * workflow code against the event history, then atomically persist whatever the
 * code decided (record side effects, schedule the next activity, or finish).
 *
 * Because the decision is pure replay, it is always safe to run again — which is
 * precisely why crash recovery is "just" re-running the decision.
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final EventStore eventStore;
    private final WorkflowExecutor executor;
    private final WorkflowRegistry registry;
    private final WorkflowInstanceRepository instances;
    private final ActivityTaskRepository activityTasks;
    private final WorkflowTaskRepository workflowTasks;
    private final ParadoxResolutionService healing;
    private final Json json;

    public WorkflowEngine(EventStore eventStore, WorkflowExecutor executor, WorkflowRegistry registry,
                          WorkflowInstanceRepository instances, ActivityTaskRepository activityTasks,
                          WorkflowTaskRepository workflowTasks, ParadoxResolutionService healing,
                          Json json) {
        this.eventStore = eventStore;
        this.executor = executor;
        this.registry = registry;
        this.instances = instances;
        this.activityTasks = activityTasks;
        this.workflowTasks = workflowTasks;
        this.healing = healing;
        this.json = json;
    }

    /** Create a new workflow execution and enqueue its first decision. */
    @Transactional
    public String startWorkflow(String workflowType, String inputJson, String requestedId) {
        if (!registry.contains(workflowType)) {
            throw new IllegalArgumentException("Unknown workflow type: " + workflowType);
        }
        String workflowId = (requestedId != null && !requestedId.isBlank())
                ? requestedId : UUID.randomUUID().toString();

        if (instances.findById(workflowId).isPresent()) {
            // Idempotent start: same id -> no-op, return existing.
            return workflowId;
        }

        WorkflowInstanceEntity instance = new WorkflowInstanceEntity(workflowId, workflowType, inputJson);
        instances.save(instance);
        eventStore.appendLocked(instance, EventType.WORKFLOW_STARTED,
                new Payloads.WorkflowStarted(workflowType, inputJson));
        instances.save(instance);

        workflowTasks.save(new WorkflowTaskEntity(workflowId));
        log.info("Started workflow {} of type {}", workflowId, workflowType);
        return workflowId;
    }

    /**
     * Process one decision for a workflow. Idempotent: terminal workflows and
     * re-delivered tasks are no-ops.
     */
    @Transactional
    public void processDecision(String workflowId) {
        WorkflowInstanceEntity instance = instances.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new IllegalStateException("Unknown workflow: " + workflowId));

        if (instance.getStatus() != WorkflowStatus.RUNNING) {
            return; // already finished
        }

        List<WorkflowEventEntity> history = eventStore.history(workflowId);
        ReplayState state = rebuild(history);

        Workflow workflow = registry.get(instance.getWorkflowType());

        // Paradox Resolution Engine: align the deployed code graph with recorded
        // history. For unchanged code the session is a pure pass-through; when a
        // structural divergence is intercepted its micro-patch mappings are
        // committed below, in this same transaction, under the instance lock.
        SequenceAlignmentSession alignment = healing.openSession(workflowId, history);
        Commands.Decision decision = executor.runDecision(
                workflow, workflowId, instance.getInput(),
                state.completed, state.failed, state.sideEffects, state.scheduled, alignment);
        healing.commitResolutions(alignment, instance);

        // Persist any non-deterministic values the workflow captured this run.
        for (Commands.RecordSideEffect se : decision.sideEffects()) {
            eventStore.appendLocked(instance, EventType.SIDE_EFFECT_RECORDED,
                    new Payloads.SideEffectRecorded(se.commandSeq(), se.value()));
        }

        switch (decision.kind()) {
            case SCHEDULE -> scheduleActivity(instance, decision.schedule());
            case COMPLETE -> {
                eventStore.appendLocked(instance, EventType.WORKFLOW_COMPLETED,
                        new Payloads.WorkflowCompleted(decision.result()));
                instance.setStatus(WorkflowStatus.COMPLETED);
                instance.setResult(decision.result());
                log.info("Workflow {} completed", workflowId);
            }
            case FAIL -> {
                eventStore.appendLocked(instance, EventType.WORKFLOW_FAILED,
                        new Payloads.WorkflowFailed(decision.error()));
                instance.setStatus(WorkflowStatus.FAILED);
                instance.setError(decision.error());
                log.warn("Workflow {} failed: {}", workflowId, decision.error());
            }
            case BLOCKED -> { /* waiting on an in-flight activity */ }
        }
        instances.save(instance);
    }

    private void scheduleActivity(WorkflowInstanceEntity instance, Commands.ScheduleActivity s) {
        String idempotencyKey = instance.getWorkflowId() + ":" + s.commandSeq();
        eventStore.appendLocked(instance, EventType.ACTIVITY_SCHEDULED,
                new Payloads.ActivityScheduled(s.commandSeq(), s.activityType(), s.input(),
                        s.maxAttempts(), s.timeoutSeconds(), idempotencyKey));
        activityTasks.save(new ActivityTaskEntity(
                instance.getWorkflowId(), s.activityType(), s.commandSeq(), s.input(),
                s.maxAttempts(), s.timeoutSeconds(), idempotencyKey));
        log.info("Scheduled activity {} (seq {}) for workflow {}",
                s.activityType(), s.commandSeq(), instance.getWorkflowId());
    }

    /** Fold the event history into the maps the executor needs for replay. */
    private ReplayState rebuild(List<WorkflowEventEntity> history) {
        ReplayState st = new ReplayState();
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
                default -> { /* other events do not affect replay decisions */ }
            }
        }
        return st;
    }

    private static final class ReplayState {
        final Map<Long, String> completed = new HashMap<>();
        final Map<Long, String> failed = new HashMap<>();
        final Map<Long, String> sideEffects = new HashMap<>();
        final Set<Long> scheduled = new HashSet<>();
    }
}
