package io.continuum.core.event;

import io.continuum.common.Json;
import io.continuum.persistence.entity.WorkflowEventEntity;
import io.continuum.persistence.entity.WorkflowInstanceEntity;
import io.continuum.persistence.repository.WorkflowEventRepository;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Appends to and reads from the immutable workflow event log.
 *
 * Every append takes a pessimistic write lock on the workflow's instance row.
 * That lock is the per-workflow serialization point: it guarantees event
 * sequence numbers are gap-free and monotonic, and that a workflow decision and
 * an activity completion for the same workflow can never interleave and corrupt
 * each other. Different workflows never contend, so throughput scales with the
 * number of distinct workflows.
 */
@Service
public class EventStore {

    private final WorkflowEventRepository events;
    private final WorkflowInstanceRepository instances;
    private final Json json;

    public EventStore(WorkflowEventRepository events, WorkflowInstanceRepository instances, Json json) {
        this.events = events;
        this.instances = instances;
        this.json = json;
    }

    /**
     * Append one event under the workflow's instance lock and advance the
     * sequence cursor. Must be called inside a transaction.
     */
    public WorkflowEventEntity append(String workflowId, EventType type, Object payload) {
        WorkflowInstanceEntity instance = instances.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new IllegalStateException("Unknown workflow: " + workflowId));
        return appendLocked(instance, type, payload);
    }

    /** Append using an already-locked instance (avoids re-locking within a batch). */
    public WorkflowEventEntity appendLocked(WorkflowInstanceEntity instance, EventType type, Object payload) {
        long seq = instance.getCurrentSequence();
        String body = payload == null ? null : json.write(payload);
        WorkflowEventEntity event = new WorkflowEventEntity(instance.getWorkflowId(), seq, type, body);
        WorkflowEventEntity saved = events.save(event);
        instance.setCurrentSequence(seq + 1);
        return saved;
    }

    public List<WorkflowEventEntity> history(String workflowId) {
        return events.findByWorkflowIdOrderBySequenceNumberAsc(workflowId);
    }
}
