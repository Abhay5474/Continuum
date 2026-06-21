package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Record of one AI failure injection — the basis for survival/recovery metrics. */
@Entity
@Table(name = "ai_chaos_events",
        indexes = {
                @Index(name = "idx_aichaos_workflow", columnList = "workflow_id"),
                @Index(name = "idx_aichaos_type", columnList = "failure_type")
        })
public class AiChaosEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", length = 64)
    private String workflowId;

    @Column(name = "failure_type", nullable = false, length = 30)
    private String failureType;

    @Column(name = "command_seq")
    private Long commandSeq;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AiChaosEventEntity() {
    }

    public AiChaosEventEntity(String workflowId, String failureType, Long commandSeq, String detail) {
        this.workflowId = workflowId;
        this.failureType = failureType;
        this.commandSeq = commandSeq;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getWorkflowId() { return workflowId; }
    public String getFailureType() { return failureType; }
    public Long getCommandSeq() { return commandSeq; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
