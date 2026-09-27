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
    private final HistoryCache cache;

    public EventStore(WorkflowEventRepository events, WorkflowInstanceRepository instances, Json json,
                      HistoryCache cache) {
        this.events = events;
        this.instances = instances;
        this.json = json;
        this.cache = cache;
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

    /**
     * The history a decision replays, reading only what is new since the last
     * decision on this workflow.
     *
     * <p>Only for the start of a decision: the caller holds the instance lock
     * and has appended nothing yet in its transaction, so every event it can
     * see is committed and none can be added under it. A cached history is
     * trusted only if it still fits — no longer than the instance says the log
     * is, and starting with the same database row (a workflow id reused after
     * its run was purged starts a different log). Anything else reads the
     * whole log again.
     */
    public List<WorkflowEventEntity> historyForDecision(WorkflowInstanceEntity locked) {
        String id = locked.getWorkflowId();
        long next = locked.getCurrentSequence();
        List<WorkflowEventEntity> cached = cache.enabled() ? cache.get(id) : null;
        List<WorkflowEventEntity> history = null;
        if (cached != null && !cached.isEmpty()) {
            WorkflowEventEntity first = cached.get(0);
            long last = cached.get(cached.size() - 1).getSequenceNumber();
            boolean sameLog = last < next && events.idAt(id, first.getSequenceNumber())
                    .map(pk -> pk.equals(first.getId())).orElse(false);
            if (sameLog) {
                List<WorkflowEventEntity> newer = last + 1 == next ? List.of()
                        : events.findByWorkflowIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(id, last);
                List<WorkflowEventEntity> joined = new java.util.ArrayList<>(cached.size() + newer.size());
                joined.addAll(cached);
                joined.addAll(newer);
                if (contiguous(joined) && joined.get(joined.size() - 1).getSequenceNumber() == next - 1) {
                    history = joined;
                }
            }
        }
        if (history == null) {
            history = events.findByWorkflowIdOrderBySequenceNumberAsc(id);
        }
        cache.put(id, history);
        return history;
    }

    /** Forgets a workflow's cached history (its log was removed). */
    public void forget(String workflowId) {
        cache.remove(workflowId);
    }

    private static boolean contiguous(List<WorkflowEventEntity> h) {
        for (int i = 1; i < h.size(); i++) {
            if (h.get(i).getSequenceNumber() != h.get(i - 1).getSequenceNumber() + 1) {
                return false;
            }
        }
        return true;
    }
}
