package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A unit of non-deterministic work to be executed by a worker.
 *
 * This table IS the durable task queue. Workers claim rows with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} so that many workers can poll
 * concurrently without stepping on each other, and a crashed worker's task
 * becomes claimable again once its {@code lockedUntil} visibility timeout
 * lapses.
 */
@Entity
@Table(
        name = "activity_tasks",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_activity_task_seq",
                columnNames = {"workflow_id", "sequence_number"}
        ),
        indexes = {
                @Index(name = "idx_activity_claim", columnList = "status, visible_at"),
                @Index(name = "idx_activity_workflow", columnList = "workflow_id")
        }
)
public class ActivityTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(name = "activity_type", nullable = false, length = 200)
    private String activityType;

    /** Matches the activity's position in the deterministic workflow history. */
    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Column(name = "input", columnDefinition = "text")
    private String input;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 1;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 30;

    /** Deterministic key used to make this activity's side effects idempotent. */
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    /** Task becomes claimable when now >= visibleAt (used for retry backoff). */
    @Column(name = "visible_at", nullable = false)
    private Instant visibleAt = Instant.now();

    /** While RUNNING, the task is invisible until this instant (visibility timeout). */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "locked_by", length = 64)
    private String lockedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ActivityTaskEntity() {
    }

    public ActivityTaskEntity(String workflowId, String activityType, long sequenceNumber,
                              String input, int maxAttempts, int timeoutSeconds, String idempotencyKey) {
        this.workflowId = workflowId;
        this.activityType = activityType;
        this.sequenceNumber = sequenceNumber;
        this.input = input;
        this.maxAttempts = maxAttempts;
        this.timeoutSeconds = timeoutSeconds;
        this.idempotencyKey = idempotencyKey;
        this.status = TaskStatus.PENDING;
        this.visibleAt = Instant.now();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getActivityType() {
        return activityType;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public String getInput() {
        return input;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
