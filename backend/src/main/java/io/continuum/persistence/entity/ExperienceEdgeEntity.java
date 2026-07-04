package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Edge of the semantic experience graph connecting related experiences. */
@Entity
@Table(name = "memory_experience_edges", indexes = {
        @Index(name = "idx_mem_edges_dev", columnList = "developer_id"),
        @Index(name = "idx_mem_edges_from", columnList = "from_node_id")})
public class ExperienceEdgeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "from_node_id", nullable = false)
    private Long fromNodeId;

    @Column(name = "to_node_id", nullable = false)
    private Long toNodeId;

    @Column(name = "relation", nullable = false, length = 32)
    private String relation = "SIMILAR";

    @Column(name = "weight", nullable = false)
    private double weight;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ExperienceEdgeEntity() {
    }

    public ExperienceEdgeEntity(String developerId, Long fromNodeId, Long toNodeId,
                                String relation, double weight) {
        this.developerId = developerId;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.relation = relation;
        this.weight = weight;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public Long getFromNodeId() { return fromNodeId; }
    public Long getToNodeId() { return toNodeId; }
    public String getRelation() { return relation; }
    public double getWeight() { return weight; }
    public Instant getCreatedAt() { return createdAt; }
}
