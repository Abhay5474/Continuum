package io.continuum.persistence.entity;

import jakarta.persistence.*;

/** One edge of a projected V6 DAG trace. */
@Entity
@Table(name = "dag_edges",
        indexes = @Index(name = "idx_dag_edges_wf", columnList = "workflow_id"))
public class DagEdgeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(name = "from_key", nullable = false, length = 64)
    private String fromKey;

    @Column(name = "to_key", nullable = false, length = 64)
    private String toKey;

    @Column(name = "edge_type", nullable = false, length = 16)
    private String edgeType;

    @Column(name = "weight", nullable = false)
    private double weight;

    protected DagEdgeEntity() {
    }

    public DagEdgeEntity(String workflowId, String fromKey, String toKey, String edgeType, double weight) {
        this.workflowId = workflowId;
        this.fromKey = fromKey;
        this.toKey = toKey;
        this.edgeType = edgeType;
        this.weight = weight;
    }

    public Long getId() { return id; }
    public String getWorkflowId() { return workflowId; }
    public String getFromKey() { return fromKey; }
    public String getToKey() { return toKey; }
    public String getEdgeType() { return edgeType; }
    public double getWeight() { return weight; }
}
