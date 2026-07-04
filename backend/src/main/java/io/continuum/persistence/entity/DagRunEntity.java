package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Projection of one V6 Consensus DAG run for the trace UI (event log is the truth). */
@Entity
@Table(name = "dag_runs",
        indexes = @Index(name = "idx_dag_runs_dev", columnList = "developer_id, created_at"))
public class DagRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, length = 64, unique = true)
    private String workflowId;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "prompt", columnDefinition = "text")
    private String prompt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "final_confidence")
    private Double finalConfidence;

    @Column(name = "uncertainty", length = 12)
    private String uncertainty;

    @Column(name = "verdict", columnDefinition = "text")
    private String verdict;

    @Column(name = "risk_flags_json", columnDefinition = "text")
    private String riskFlagsJson;

    @Column(name = "claim_count", nullable = false)
    private int claimCount;

    @Column(name = "node_count", nullable = false)
    private int nodeCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected DagRunEntity() {
    }

    public DagRunEntity(String workflowId, String developerId, String prompt, String status) {
        this.workflowId = workflowId;
        this.developerId = developerId;
        this.prompt = prompt;
        this.status = status;
    }

    public Long getId() { return id; }
    public String getWorkflowId() { return workflowId; }
    public String getDeveloperId() { return developerId; }
    public String getPrompt() { return prompt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Double getFinalConfidence() { return finalConfidence; }
    public void setFinalConfidence(Double v) { this.finalConfidence = v; }
    public String getUncertainty() { return uncertainty; }
    public void setUncertainty(String v) { this.uncertainty = v; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String v) { this.verdict = v; }
    public String getRiskFlagsJson() { return riskFlagsJson; }
    public void setRiskFlagsJson(String v) { this.riskFlagsJson = v; }
    public int getClaimCount() { return claimCount; }
    public void setClaimCount(int v) { this.claimCount = v; }
    public int getNodeCount() { return nodeCount; }
    public void setNodeCount(int v) { this.nodeCount = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
}
