package io.continuum.core.activity;

import io.continuum.common.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything an activity needs to do its (non-deterministic) job.
 *
 * Crucially, external side effects are NOT performed inline. An activity that
 * wants to send an email or charge a card calls {@link #enqueueOutbox}; the
 * message is written to the transactional outbox in the same transaction that
 * records the activity's completion event, and a separate dispatcher delivers
 * it exactly once. Likewise {@link #recordCost} buffers token/cost accounting
 * to be committed atomically with completion.
 */
public class ActivityContext {

    private final String workflowId;
    private final String idempotencyKey;
    private final int attempt;
    private final Json json;

    private final List<OutboxMessage> outbox = new ArrayList<>();
    private final List<CostEntry> costs = new ArrayList<>();

    public ActivityContext(String workflowId, String idempotencyKey, int attempt, Json json) {
        this.workflowId = workflowId;
        this.idempotencyKey = idempotencyKey;
        this.attempt = attempt;
        this.json = json;
    }

    public String workflowId() {
        return workflowId;
    }

    /**
     * Deterministic idempotency key for this activity invocation
     * ({@code workflowId:commandSeq}). Stable across retries and replays, which
     * is exactly what makes side effects safe to deliver at-most-once.
     */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    /** 1-based attempt number (increases on retry). Useful for chaos/testing. */
    public int attempt() {
        return attempt;
    }

    public <T> T input(String inputJson, Class<T> type) {
        return json.read(inputJson, type);
    }

    /** Buffer an external message for at-most-once delivery via the outbox. */
    public void enqueueOutbox(String destination, String eventType, Object payload) {
        String key = idempotencyKey + "#" + outbox.size();
        outbox.add(new OutboxMessage(destination, eventType, json.write(payload), key));
    }

    /** Buffer LLM token/cost accounting to commit with the completion event. */
    public void recordCost(String provider, String model, int promptTokens, int completionTokens, double costUsd) {
        costs.add(new CostEntry(provider, model, promptTokens, completionTokens, costUsd));
    }

    public List<OutboxMessage> outboxMessages() {
        return outbox;
    }

    public List<CostEntry> costEntries() {
        return costs;
    }

    public record OutboxMessage(String destination, String eventType, String payload, String idempotencyKey) {
    }

    public record CostEntry(String provider, String model, int promptTokens, int completionTokens, double costUsd) {
    }
}
