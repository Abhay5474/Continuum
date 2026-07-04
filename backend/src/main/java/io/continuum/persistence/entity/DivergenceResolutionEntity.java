package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Denormalized observability record of one resolved determinism divergence,
 * consumed by the control-plane healing APIs and dashboard.
 */
@Entity
@Table(name = "autopilot_divergence_resolutions",
        indexes = @Index(name = "idx_divergence_res_wf", columnList = "workflow_id"))
public class DivergenceResolutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(name = "workflow_type", length = 200)
    private String workflowType;

    @Column(name = "resolution_type", nullable = false, length = 32)
    private String resolutionType;

    @Column(name = "code_sequence", nullable = false)
    private long codeSequence;

    @Column(name = "history_sequence")
    private Long historySequence;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt = Instant.now();

    protected DivergenceResolutionEntity() {
    }

    public DivergenceResolutionEntity(String workflowId, String workflowType, String resolutionType,
                                      long codeSequence, Long historySequence, String detail) {
        this.workflowId = workflowId;
        this.workflowType = workflowType;
        this.resolutionType = resolutionType;
        this.codeSequence = codeSequence;
        this.historySequence = historySequence;
        this.detail = detail;
        this.resolvedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getWorkflowType() {
        return workflowType;
    }

    public String getResolutionType() {
        return resolutionType;
    }

    public long getCodeSequence() {
        return codeSequence;
    }

    public Long getHistorySequence() {
        return historySequence;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
