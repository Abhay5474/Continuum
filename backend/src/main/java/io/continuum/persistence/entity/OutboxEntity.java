package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Transactional outbox record.
 *
 * External side effects (emails, notifications, webhooks) are NOT performed
 * inline by activities. Instead the activity writes an outbox row in the SAME
 * database transaction that records the {@code ACTIVITY_COMPLETED} event. A
 * separate dispatcher then delivers the message and marks it SENT.
 *
 * This guarantees the event log and the intent-to-send commit atomically (no
 * lost messages), while the unique {@code idempotencyKey} guarantees a message
 * is delivered at-most-once even if the dispatcher crashes mid-flight (no
 * duplicate effects).
 */
@Entity
@Table(
        name = "outbox",
        uniqueConstraints = @UniqueConstraint(name = "uq_outbox_idempotency", columnNames = "idempotency_key"),
        indexes = @Index(name = "idx_outbox_status", columnList = "status, visible_at")
)
public class OutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", length = 64)
    private String workflowId;

    @Column(name = "destination", nullable = false, length = 100)
    private String destination;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "visible_at", nullable = false)
    private Instant visibleAt = Instant.now();

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    protected OutboxEntity() {
    }

    public OutboxEntity(String workflowId, String destination, String eventType,
                        String payload, String idempotencyKey) {
        this.workflowId = workflowId;
        this.destination = destination;
        this.eventType = eventType;
        this.payload = payload;
        this.idempotencyKey = idempotencyKey;
        this.status = OutboxStatus.PENDING;
        this.visibleAt = Instant.now();
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getDestination() {
        return destination;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public void setStatus(OutboxStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getVisibleAt() {
        return visibleAt;
    }

    public void setVisibleAt(Instant visibleAt) {
        this.visibleAt = visibleAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(Instant dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
