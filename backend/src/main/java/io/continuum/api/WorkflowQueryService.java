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
    private final WorkflowTaskRepository workflowTasks;
    private final Json json;

    public WorkflowQueryService(WorkflowInstanceRepository instances, WorkflowEventRepository events,
                                ActivityTaskRepository activityTasks, OutboxRepository outbox,
                                CostRecordRepository costs, WorkflowTaskRepository workflowTasks, Json json) {
        this.workflowTasks = workflowTasks;
        this.instances = instances;
        this.events = events;
        this.activityTasks = activityTasks;
        this.outbox = outbox;
        this.costs = costs;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<WorkflowSummary> list(int limit) {
        return summarise(instances.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)).getContent());
    }

    /** Only the workflows owned by {@code developerId} — what the console shows a tenant. */
    @Transactional(readOnly = true)
    public List<WorkflowSummary> listForDeveloper(String developerId, int limit) {
        return summarise(instances.findByDeveloperIdOrderByCreatedAtDesc(developerId, PageRequest.of(0, limit))
                .getContent());
    }

    /** Summaries for a page of workflows, with one query to find which are stuck. */
    private List<WorkflowSummary> summarise(List<WorkflowInstanceEntity> page) {
        List<String> running = page.stream()
                .filter(i -> i.getStatus() == io.continuum.persistence.entity.WorkflowStatus.RUNNING)
                .map(WorkflowInstanceEntity::getWorkflowId).toList();
        java.util.Set<String> stuck = running.isEmpty() ? java.util.Set.of()
                : new java.util.HashSet<>(workflowTasks.stuckAmong(running));
        return page.stream().map(i -> toSummary(i, stuck.contains(i.getWorkflowId()))).toList();
    }

    /** Running workflows parked on a failing decision, engine-wide. */
    @Transactional(readOnly = true)
    public long stuckCount() {
        return workflowTasks.countStuck();
    }

    /** How {@code WorkflowEngine#cancel} words the reason; what marks a cancel. */
    public static final String CANCELLED_PREFIX = "Cancelled:";

    /** A workflow's owner, type and status, without its history. */
    @Transactional(readOnly = true)
    public java.util.Optional<Existing> find(String workflowId) {
        return instances.findById(workflowId)
                .map(i -> new Existing(i.getDeveloperId(), i.getWorkflowType(), i.getStatus().name()));
    }

    public record Existing(String ownerId, String workflowType, String status) {
    }

    /** The input exactly as the run was started with it, for a rerun. */
    @Transactional(readOnly = true)
    public String rawInput(String workflowId) {
        return instances.findById(workflowId).map(WorkflowInstanceEntity::getInput)
                .orElseThrow(io.continuum.portal.RequestScope.NotFoundException::new);
    }

    /** The owning developer of a workflow, for authorization checks. */
    @Transactional(readOnly = true)
    public String ownerOf(String workflowId) {
        return instances.findById(workflowId)
                .orElseThrow(io.continuum.portal.RequestScope.NotFoundException::new)
                .getDeveloperId();
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

        StuckView stuck = instance.getStatus() != io.continuum.persistence.entity.WorkflowStatus.RUNNING ? null
                : workflowTasks.findFirstByWorkflowIdAndStatusOrderByUpdatedAtDesc(workflowId, TaskStatus.FAILED)
                        .map(t -> new StuckView(t.getAttempts(), t.getLastError(), t.getUpdatedAt()))
                        .orElse(null);

        return new WorkflowDetail(toSummary(instance, stuck != null), parse(instance.getInput()),
                parse(instance.getResult()), instance.getError(), eventViews, activityViews, outboxViews,
                cost, tokens, stuck);
    }

    @Transactional(readOnly = true)
    public CostReport costReport() {
        List<CostByProvider> byProvider = costs.costByProvider().stream()
                .map(v -> new CostByProvider(v.getProvider(), v.getTokens(), v.getCost(), v.getCalls()))
                .toList();
        return new CostReport(costs.totalCost(), costs.totalTokens(), byProvider);
    }

    /** Cost report limited to the workflows the given developer owns. */
    @Transactional(readOnly = true)
    public CostReport costReportForDeveloper(String developerId) {
        List<CostByProvider> byProvider = costs.costByProviderForDeveloper(developerId).stream()
                .map(v -> new CostByProvider(v.getProvider(), v.getTokens(), v.getCost(), v.getCalls()))
                .toList();
        return new CostReport(costs.totalCostForDeveloper(developerId),
                costs.totalTokensForDeveloper(developerId), byProvider);
    }

    private WorkflowSummary toSummary(WorkflowInstanceEntity i, boolean stuck) {
        return new WorkflowSummary(i.getWorkflowId(), i.getWorkflowType(), i.getStatus().name(),
                i.getCurrentSequence(), i.getCreatedAt(), i.getUpdatedAt(),
                i.getStatus() == io.continuum.persistence.entity.WorkflowStatus.FAILED
                        && i.getError() != null && i.getError().startsWith(CANCELLED_PREFIX), stuck);
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
