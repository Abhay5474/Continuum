package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** One detected loop, with the steps it is accusing. */
@Entity
@Table(name = "loop_event",
        indexes = @Index(name = "idx_loop_event_dev", columnList = "developer_id,created_at"))
public class LoopEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "workflow_id", length = 64)
    private String workflowId;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "evidence", length = 1000)
    private String evidence;

    @Column(name = "halted", nullable = false)
    private boolean halted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected LoopEventEntity() {
    }

    public LoopEventEntity(String developerId, String workflowId, String kind, int stepIndex,
                           double confidence, String reason, String evidence, boolean halted) {
        this.developerId = developerId;
        this.workflowId = workflowId;
        this.kind = kind;
        this.stepIndex = stepIndex;
        this.confidence = confidence;
        this.reason = clip(reason, 500);
        this.evidence = clip(evidence, 1000);
        this.halted = halted;
    }

    private static String clip(String v, int max) {
        return v == null ? null : v.substring(0, Math.min(max, v.length()));
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getWorkflowId() { return workflowId; }
    public String getKind() { return kind; }
    public int getStepIndex() { return stepIndex; }
    public double getConfidence() { return confidence; }
    public String getReason() { return reason; }
    public String getEvidence() { return evidence; }
    public boolean isHalted() { return halted; }
    public Instant getCreatedAt() { return createdAt; }
}
