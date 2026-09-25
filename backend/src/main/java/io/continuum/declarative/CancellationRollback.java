package io.continuum.declarative;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.core.engine.WorkflowEngine;
import io.continuum.core.event.EventStore;
import io.continuum.core.event.EventType;
import io.continuum.persistence.entity.WorkflowEventEntity;
import io.continuum.persistence.entity.WorkflowInstanceEntity;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * After a declarative run is cancelled, starts the run that undoes it.
 *
 * <p>Only for runs that were started with saga rollback on — the setting is
 * pinned into each run, and a run started with it off keeps the promise it was
 * started with: nothing is undone.
 *
 * <p>What completed is read from the cancelled run's own history, the same
 * record its replay is built from, so the rollback undoes exactly the steps the
 * run would have undone had one of its steps failed at that moment.
 */
@Service
public class CancellationRollback {

    private static final Logger log = LoggerFactory.getLogger(CancellationRollback.class);
    /** The rollback's id is derived, so cancelling twice cannot start two. */
    public static final String SUFFIX = ":rollback";

    private final WorkflowEngine engine;
    private final EventStore events;
    private final WorkflowInstanceRepository instances;
    private final ObjectMapper mapper;

    public CancellationRollback(WorkflowEngine engine, EventStore events, WorkflowInstanceRepository instances,
                                ObjectMapper mapper) {
        this.engine = engine;
        this.events = events;
        this.instances = instances;
        this.mapper = mapper;
    }

    /** @return the rollback run's id, or empty when there is nothing to roll back */
    public Optional<String> afterCancel(String workflowId, String reason) {
        WorkflowInstanceEntity instance = instances.findById(workflowId).orElse(null);
        if (instance == null || !DeclarativeWorkflow.TYPE.equals(instance.getWorkflowType())) {
            return Optional.empty();
        }
        DeclarativeWorkflow.Run run;
        try {
            run = mapper.readValue(instance.getInput(), DeclarativeWorkflow.Run.class);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (run == null || run.spec() == null || !run.compensate()) {
            return Optional.empty();
        }

        Progress p = progress(workflowId);
        if (p.completed().isEmpty() && p.inFlight().isEmpty()) {
            return Optional.empty(); // nothing had happened yet
        }
        DeclarativeRollbackWorkflow.Input input = new DeclarativeRollbackWorkflow.Input(
                workflowId, run.definition(), run.version(), run.developerId(), run.spec(), run.input(),
                p.results(), p.completed(), p.inFlight(), reason);
        try {
            String id = engine.startWorkflow(DeclarativeRollbackWorkflow.TYPE, mapper.writeValueAsString(input),
                    workflowId + SUFFIX, instance.getDeveloperId());
            log.info("Started rollback {} for cancelled run {} ({} completed, {} in flight)",
                    id, workflowId, p.completed().size(), p.inFlight().size());
            return Optional.of(id);
        } catch (Exception e) {
            // The cancel stands either way; say so loudly, because effects remain.
            log.error("Could not start the rollback for cancelled run {}", workflowId, e);
            throw new IllegalStateException("The run was stopped, but its rollback could not be started: "
                    + e.getMessage());
        }
    }

    record Progress(Map<String, Object> results, List<String> completed, List<String> inFlight) {
    }

    /** Forward steps that completed, in completion order, and those still running. */
    Progress progress(String workflowId) {
        Map<Long, String> stepBySeq = new LinkedHashMap<>();
        Set<Long> waits = new java.util.HashSet<>();
        Map<String, Object> results = new LinkedHashMap<>();
        Set<Long> finished = new java.util.HashSet<>();
        Set<Long> started = new java.util.HashSet<>();
        for (WorkflowEventEntity e : events.history(workflowId)) {
            Map<String, Object> payload = read(e.getPayload());
            Object seqValue = payload.get("commandSeq");
            if (!(seqValue instanceof Number n)) {
                continue;
            }
            long seq = n.longValue();
            String type = String.valueOf(payload.get("activityType"));
            if (e.getEventType() == EventType.ACTIVITY_SCHEDULED) {
                Map<String, Object> in = read(String.valueOf(payload.get("input")));
                if (HttpStepActivity.TYPE.equals(type)) {
                    String key = String.valueOf(in.get("idempotencyKey"));
                    String prefix = workflowId + ":";
                    // Forward steps only: compensations and the completion
                    // callback carry keys of their own shape.
                    if (key.startsWith(prefix) && !key.contains(":compensate:") && !key.endsWith(":onComplete")) {
                        stepBySeq.put(seq, key.substring(prefix.length()));
                    }
                } else if (WaitStepActivity.TYPE.equals(type) && in.get("stepId") != null) {
                    stepBySeq.put(seq, String.valueOf(in.get("stepId")));
                    waits.add(seq);
                }
            } else if (e.getEventType() == EventType.ACTIVITY_STARTED) {
                started.add(seq);
            } else if (e.getEventType() == EventType.ACTIVITY_COMPLETED && stepBySeq.containsKey(seq)) {
                finished.add(seq);
                Map<String, Object> r = read(String.valueOf(payload.get("result")));
                Object body = r.get("body");
                // As the forward path stores it, so ${steps.x.field} resolves the same.
                results.put(stepBySeq.get(seq), body instanceof Map ? body : Map.of("value", r));
            } else if (e.getEventType() == EventType.ACTIVITY_FAILED && Boolean.TRUE.equals(payload.get("terminal"))) {
                finished.add(seq); // failed outright: treated as not having happened, as a forward failure is
            }
        }
        List<String> completed = new ArrayList<>(results.keySet());
        Set<String> inFlight = new LinkedHashSet<>();
        stepBySeq.forEach((seq, step) -> {
            // Scheduled but never started — withdrawn by the cancel — never ran.
            if (started.contains(seq) && !finished.contains(seq) && !waits.contains(seq)) {
                inFlight.add(step);
            }
        });
        return new Progress(results, completed, new ArrayList<>(inFlight));
    }

    private Map<String, Object> read(String json) {
        try {
            Map<String, Object> m = mapper.readValue(json, new TypeReference<Map<String, Object>>() { });
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
