package io.continuum.persistence.entity;

import io.continuum.core.event.EventType;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * An immutable, append-only record in the workflow event log.
 *
 * The pair (workflowId, sequenceNumber) is unique and gives a total order of
 * events within a workflow. This ordering is what makes deterministic replay
 * possible: replaying events 0..N always reconstructs the same state.
 */
@Entity
@Table(
        name = "workflow_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_workflow_event_seq",
                columnNames = {"workflow_id", "sequence_number"}
        ),
        indexes = @Index(name = "idx_events_workflow", columnList = "workflow_id")
)
public class WorkflowEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private EventType eventType;

    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected WorkflowEventEntity() {
    }

    public WorkflowEventEntity(String workflowId, long sequenceNumber, EventType eventType, String payload) {
        this.workflowId = workflowId;
        this.sequenceNumber = sequenceNumber;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
