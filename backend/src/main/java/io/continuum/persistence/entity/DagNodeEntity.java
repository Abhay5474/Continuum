package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** One node of a projected V6 DAG trace. */
@Entity
@Table(name = "dag_nodes",
        uniqueConstraints = @UniqueConstraint(name = "uq_dag_node",
                columnNames = {"workflow_id", "node_key"}),
        indexes = @Index(name = "idx_dag_nodes_wf", columnList = "workflow_id"))
public class DagNodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(name = "node_key", nullable = false, length = 64)
    private String nodeKey;

    @Column(name = "node_type", nullable = false, length = 20)
    private String nodeType;

    @Column(name = "claim_id")
    private Integer claimId;

    @Column(name = "label", length = 300)
    private String label;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "validity")
    private Double validity;

    @Column(name = "output_json", columnDefinition = "text")
    private String outputJson;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected DagNodeEntity() {
    }

    public DagNodeEntity(String workflowId, String nodeKey, String nodeType, Integer claimId,
                         String label, String status, Double validity, String outputJson,
                         Instant startedAt, Instant completedAt) {
        this.workflowId = workflowId;
        this.nodeKey = nodeKey;
        this.nodeType = nodeType;
        this.claimId = claimId;
        this.label = label;
        this.status = status;
        this.validity = validity;
        this.outputJson = outputJson;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public Long getId() { return id; }
    public String getWorkflowId() { return workflowId; }
    public String getNodeKey() { return nodeKey; }
    public String getNodeType() { return nodeType; }
    public Integer getClaimId() { return claimId; }
    public String getLabel() { return label; }
    public String getStatus() { return status; }
    public Double getValidity() { return validity; }
    public String getOutputJson() { return outputJson; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
