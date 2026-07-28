package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One rollback, and — more importantly — what it could not roll back.
 *
 * <p>{@code uncompensated} is the column an operator actually needs. A workflow
 * that charged a card and sent a confirmation email can refund the charge and
 * cannot unsend the email, and if that is not written down nobody will ever know
 * it happened.
 */
@Entity
@Table(name = "saga_event",
        indexes = @Index(name = "idx_saga_event_dev", columnList = "developer_id,created_at"))
public class SagaEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "workflow_id", length = 64)
    private String workflowId;

    @Column(name = "definition", length = 120)
    private String definition;

    @Column(name = "failed_step", length = 120)
    private String failedStep;

    @Column(name = "compensated", length = 1000)
    private String compensated;

    @Column(name = "uncompensated", length = 1000)
    private String uncompensated;

    @Column(name = "complete", nullable = false)
    private boolean complete;

    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected SagaEventEntity() {
    }

    public SagaEventEntity(String developerId, String workflowId, String definition,
                           String failedStep, String compensated, String uncompensated,
                           boolean complete, String summary) {
        this.developerId = developerId;
        this.workflowId = workflowId;
        this.definition = clip(definition, 120);
        this.failedStep = clip(failedStep, 120);
        this.compensated = clip(compensated, 1000);
        this.uncompensated = clip(uncompensated, 1000);
        this.complete = complete;
        this.summary = clip(summary, 500);
    }

    private static String clip(String v, int max) {
        return v == null ? null : v.substring(0, Math.min(max, v.length()));
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getWorkflowId() { return workflowId; }
    public String getDefinition() { return definition; }
    public String getFailedStep() { return failedStep; }
    public String getCompensated() { return compensated; }
    public String getUncompensated() { return uncompensated; }
    public boolean isComplete() { return complete; }
    public String getSummary() { return summary; }
    public Instant getCreatedAt() { return createdAt; }
}
