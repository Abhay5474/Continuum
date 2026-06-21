package io.continuum.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.continuum.api.dto.Dtos.*;
import io.continuum.common.Json;
import io.continuum.persistence.entity.*;
import io.continuum.persistence.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Read-side service that assembles dashboard views from the event log and projections. */
@Service
public class WorkflowQueryService {

    private final WorkflowInstanceRepository instances;
    private final WorkflowEventRepository events;
    private final ActivityTaskRepository activityTasks;
    private final OutboxRepository outbox;
    private final CostRecordRepository costs;
    private final Json json;

    public WorkflowQueryService(WorkflowInstanceRepository instances, WorkflowEventRepository events,
                                ActivityTaskRepository activityTasks, OutboxRepository outbox,
                                CostRecordRepository costs, Json json) {
        this.instances = instances;
        this.events = events;
        this.activityTasks = activityTasks;
        this.outbox = outbox;
        this.costs = costs;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<WorkflowSummary> list(int limit) {
        return instances.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit))
                .map(this::toSummary).getContent();
    }

    @Transactional(readOnly = true)
    public WorkflowDetail detail(String workflowId) {
        WorkflowInstanceEntity instance = instances.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("No such workflow: " + workflowId));

        List<EventView> eventViews = events.findByWorkflowIdOrderBySequenceNumberAsc(workflowId).stream()
                .map(e -> new EventView(e.getSequenceNumber(), e.getEventType().name(),
                        parse(e.getPayload()), e.getCreatedAt()))
                .toList();

        List<ActivityTaskView> activityViews = activityTasks.findByWorkflowIdOrderBySequenceNumberAsc(workflowId).stream()
                .map(t -> new ActivityTaskView(t.getSequenceNumber(), t.getActivityType(), t.getStatus().name(),
                        t.getRetryCount(), t.getMaxAttempts(), t.getVisibleAt(), t.getLockedBy()))
                .toList();

        List<OutboxView> outboxViews = outbox.findByWorkflowIdOrderByCreatedAtAsc(workflowId).stream()
                .map(o -> new OutboxView(o.getId(), o.getDestination(), o.getEventType(), o.getStatus().name(),
                        o.getAttempts(), o.getIdempotencyKey(), o.getCreatedAt(), o.getDispatchedAt()))
                .toList();

        double cost = costs.findByWorkflowId(workflowId).stream().mapToDouble(CostRecordEntity::getEstimatedCostUsd).sum();
        long tokens = costs.findByWorkflowId(workflowId).stream()
                .mapToLong(c -> c.getPromptTokens() + c.getCompletionTokens()).sum();

        return new WorkflowDetail(toSummary(instance), parse(instance.getInput()), parse(instance.getResult()),
                instance.getError(), eventViews, activityViews, outboxViews, cost, tokens);
    }

    @Transactional(readOnly = true)
    public CostReport costReport() {
        List<CostByProvider> byProvider = costs.costByProvider().stream()
                .map(v -> new CostByProvider(v.getProvider(), v.getTokens(), v.getCost(), v.getCalls()))
                .toList();
        return new CostReport(costs.totalCost(), costs.totalTokens(), byProvider);
    }

    private WorkflowSummary toSummary(WorkflowInstanceEntity i) {
        return new WorkflowSummary(i.getWorkflowId(), i.getWorkflowType(), i.getStatus().name(),
                i.getCurrentSequence(), i.getCreatedAt(), i.getUpdatedAt());
    }

    private Object parse(String jsonStr) {
        if (jsonStr == null) {
            return null;
        }
        try {
            return json.read(jsonStr, JsonNode.class);
        } catch (Exception e) {
            return jsonStr;
        }
    }
}
