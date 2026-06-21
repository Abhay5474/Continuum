package io.continuum.api.dto;

import java.time.Instant;
import java.util.List;

/** Request/response shapes for the REST API. */
public final class Dtos {

    private Dtos() {
    }

    public record StartWorkflowRequest(String workflowType, Object input, String workflowId) {
    }

    public record StartWorkflowResponse(String workflowId, String status) {
    }

    public record WorkflowSummary(String workflowId, String workflowType, String status,
                                  long currentSequence, Instant createdAt, Instant updatedAt) {
    }

    public record EventView(long sequenceNumber, String eventType, Object payload, Instant createdAt) {
    }

    public record ActivityTaskView(long sequenceNumber, String activityType, String status,
                                   int retryCount, int maxAttempts, Instant visibleAt, String lockedBy) {
    }

    public record OutboxView(Long id, String destination, String eventType, String status,
                             int attempts, String idempotencyKey, Instant createdAt, Instant dispatchedAt) {
    }

    public record WorkflowDetail(WorkflowSummary summary, Object input, Object result, String error,
                                 List<EventView> events, List<ActivityTaskView> activities,
                                 List<OutboxView> outbox, double costUsd, long tokens) {
    }

    public record CostByProvider(String provider, long tokens, double costUsd, long calls) {
    }

    public record CostReport(double totalCostUsd, long totalTokens, List<CostByProvider> byProvider) {
    }

    public record StatsView(long total, long running, long completed, long failed,
                            int outboxDeliveries, List<String> duplicateDeliveries) {
    }

    public record MetaView(List<String> workflowTypes, List<String> providerFailoverChain) {
    }
}
