package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One routing decision, with the counterfactual alongside it.
 *
 * <p>{@code baselineProvider} is what the heuristic scorer would have chosen for
 * the same request. Recording it turns "the bandit is learning" from a claim
 * into an arithmetic comparison over the rows where the two disagreed.
 */
@Entity
@Table(name = "routing_strategy_decision",
        indexes = @Index(name = "idx_routing_decision_dev", columnList = "developer_id, created_at"))
public class RoutingStrategyDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "strategy", nullable = false, length = 16)
    private String strategy;

    @Column(name = "context_bucket", nullable = false, length = 16)
    private String contextBucket;

    @Column(name = "complexity", nullable = false)
    private double complexity;

    @Column(name = "chosen_provider", nullable = false, length = 64)
    private String chosenProvider;

    @Column(name = "baseline_provider", length = 64)
    private String baselineProvider;

    @Column(name = "diverged", nullable = false)
    private boolean diverged;

    @Column(name = "explored", nullable = false)
    private boolean explored;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "cost", nullable = false)
    private double cost;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected RoutingStrategyDecisionEntity() {
    }

    public RoutingStrategyDecisionEntity(String developerId, String strategy, String contextBucket,
                                         double complexity, String chosenProvider, String baselineProvider,
                                         boolean explored, boolean success, long latencyMs, double cost) {
        this.developerId = developerId;
        this.strategy = strategy;
        this.contextBucket = contextBucket;
        this.complexity = complexity;
        this.chosenProvider = chosenProvider;
        this.baselineProvider = baselineProvider;
        this.diverged = baselineProvider != null && !baselineProvider.equals(chosenProvider);
        this.explored = explored;
        this.success = success;
        this.latencyMs = latencyMs;
        this.cost = cost;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getStrategy() { return strategy; }
    public String getContextBucket() { return contextBucket; }
    public double getComplexity() { return complexity; }
    public String getChosenProvider() { return chosenProvider; }
    public String getBaselineProvider() { return baselineProvider; }
    public boolean isDiverged() { return diverged; }
    public boolean isExplored() { return explored; }
    public boolean isSuccess() { return success; }
    public long getLatencyMs() { return latencyMs; }
    public double getCost() { return cost; }
    public Instant getCreatedAt() { return createdAt; }
}
