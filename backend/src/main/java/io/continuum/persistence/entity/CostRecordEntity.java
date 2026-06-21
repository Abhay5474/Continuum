package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Per-LLM-call cost and token usage, recorded when an LLM activity completes.
 *
 * Keyed by the activity idempotency key so that a replayed or retried activity
 * never double-counts tokens.
 */
@Entity
@Table(
        name = "llm_cost_records",
        uniqueConstraints = @UniqueConstraint(name = "uq_cost_idempotency", columnNames = "idempotency_key"),
        indexes = @Index(name = "idx_cost_workflow", columnList = "workflow_id")
)
public class CostRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "estimated_cost_usd", nullable = false)
    private double estimatedCostUsd;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected CostRecordEntity() {
    }

    public CostRecordEntity(String workflowId, String idempotencyKey, String provider, String model,
                            int promptTokens, int completionTokens, double estimatedCostUsd) {
        this.workflowId = workflowId;
        this.idempotencyKey = idempotencyKey;
        this.provider = provider;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.estimatedCostUsd = estimatedCostUsd;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public double getEstimatedCostUsd() {
        return estimatedCostUsd;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
