package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Observability record of a routing decision actually taken during execution. */
@Entity
@Table(name = "routing_decisions",
        indexes = {
                @Index(name = "idx_routing_workflow", columnList = "workflow_id"),
                @Index(name = "idx_routing_created", columnList = "created_at")
        })
public class RoutingDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", length = 64)
    private String workflowId;

    @Column(name = "mode", nullable = false, length = 20)
    private String mode;

    @Column(name = "complexity", nullable = false)
    private double complexity;

    @Column(name = "chosen_provider", length = 40)
    private String chosenProvider;

    @Column(name = "chain", length = 200)
    private String chain;

    @Column(name = "scores_json", columnDefinition = "text")
    private String scoresJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected RoutingDecisionEntity() {
    }

    public RoutingDecisionEntity(String workflowId, String mode, double complexity, String chosenProvider,
                                 String chain, String scoresJson) {
        this.workflowId = workflowId;
        this.mode = mode;
        this.complexity = complexity;
        this.chosenProvider = chosenProvider;
        this.chain = chain;
        this.scoresJson = scoresJson;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getWorkflowId() { return workflowId; }
    public String getMode() { return mode; }
    public double getComplexity() { return complexity; }
    public String getChosenProvider() { return chosenProvider; }
    public String getChain() { return chain; }
    public String getScoresJson() { return scoresJson; }
    public Instant getCreatedAt() { return createdAt; }

    /**
     * The tenant this row belongs to. Null only for rows recorded before
     * scoping existed, which are visible to the operator alone.
     */
    @Column(name = "developer_id", length = 64)
    private String developerId;

    public String getDeveloperId() { return developerId; }
    public void setDeveloperId(String developerId) { this.developerId = developerId; }

}
