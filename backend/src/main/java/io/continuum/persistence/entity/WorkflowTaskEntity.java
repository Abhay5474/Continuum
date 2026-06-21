package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A signal that a workflow has pending work and its deterministic decision code
 * should be (re)executed — for example after it is started, or after an
 * activity it was waiting on completes.
 *
 * Like {@link ActivityTaskEntity} this is a durable, claimable queue row, so a
 * worker crash mid-decision cannot strand a workflow: the task simply becomes
 * visible again and another worker replays it. Replay is idempotent, so
 * re-running a decision is always safe.
 */
@Entity
@Table(
        name = "workflow_tasks",
        indexes = @Index(name = "idx_workflow_task_claim", columnList = "status, visible_at")
)
public class WorkflowTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "visible_at", nullable = false)
    private Instant visibleAt = Instant.now();

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "locked_by", length = 64)
    private String lockedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected WorkflowTaskEntity() {
    }

    public WorkflowTaskEntity(String workflowId) {
        this.workflowId = workflowId;
        this.status = TaskStatus.PENDING;
        this.visibleAt = Instant.now();
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public Instant getVisibleAt() {
        return visibleAt;
    }

    public void setVisibleAt(Instant visibleAt) {
        this.visibleAt = visibleAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
