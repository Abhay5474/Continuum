package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One durable entry of a workflow instance's paradox resolution ledger.
 *
 * Written atomically inside the same transaction as the decision that healed
 * the divergence; replays load the full ledger for the instance and re-apply
 * it, keeping healed executions perfectly deterministic.
 */
@Entity
@Table(name = "workflow_healing_logs",
        uniqueConstraints = @UniqueConstraint(name = "uq_healing_wf_seq",
                columnNames = {"workflow_id", "divergence_sequence_number"}),
        indexes = @Index(name = "idx_healing_workflow", columnList = "workflow_id"))
public class WorkflowHealingLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    /** The code command sequence at which the divergence paradox occurred. */
    @Column(name = "divergence_sequence_number", nullable = false)
    private long divergenceSequenceNumber;

    @Column(name = "resolution_type", nullable = false, length = 32)
    private String resolutionType;

    /** The structural bridge configuration (serialized {@code AlignmentMapping}). */
    @Column(name = "virtualized_payload_json", columnDefinition = "text")
    private String virtualizedPayloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected WorkflowHealingLogEntity() {
    }

    public WorkflowHealingLogEntity(String workflowId, long divergenceSequenceNumber,
                                    String resolutionType, String virtualizedPayloadJson) {
        this.workflowId = workflowId;
        this.divergenceSequenceNumber = divergenceSequenceNumber;
        this.resolutionType = resolutionType;
        this.virtualizedPayloadJson = virtualizedPayloadJson;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public long getDivergenceSequenceNumber() {
        return divergenceSequenceNumber;
    }

    public String getResolutionType() {
        return resolutionType;
    }

    public String getVirtualizedPayloadJson() {
        return virtualizedPayloadJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
