package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Mutable metadata about a workflow execution.
 *
 * This row is a *projection* of the event log kept for cheap querying (status,
 * timestamps, result). It is never the source of truth — if it were lost it
 * could be rebuilt by replaying {@code workflow_events}.
 */
@Entity
@Table(name = "workflow_instances", indexes = @Index(name = "idx_instances_status", columnList = "status"))
public class WorkflowInstanceEntity {

    @Id
    @Column(name = "workflow_id", length = 64)
    private String workflowId;

    @Column(name = "workflow_type", nullable = false, length = 200)
    private String workflowType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WorkflowStatus status;

    @Column(name = "input", columnDefinition = "text")
    private String input;

    @Column(name = "result", columnDefinition = "text")
    private String result;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    /** Highest event sequence number persisted so far; the cursor for appends. */
    @Column(name = "current_sequence", nullable = false)
    private long currentSequence;

    /**
     * Owning developer, or {@code null} for system/legacy workflows. Used to scope
     * the console so a tenant only ever sees their own executions.
     */
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected WorkflowInstanceEntity() {
    }

    public WorkflowInstanceEntity(String workflowId, String workflowType, String input) {
        this.workflowId = workflowId;
        this.workflowType = workflowType;
        this.input = input;
        this.status = WorkflowStatus.RUNNING;
        this.currentSequence = 0;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getWorkflowType() {
        return workflowType;
    }

    public WorkflowStatus getStatus() {
        return status;
    }

    public void setStatus(WorkflowStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public String getInput() {
        return input;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public long getCurrentSequence() {
        return currentSequence;
    }

    public void setCurrentSequence(long currentSequence) {
        this.currentSequence = currentSequence;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getDeveloperId() {
        return developerId;
    }

    public void setDeveloperId(String developerId) {
        this.developerId = developerId;
    }
}
