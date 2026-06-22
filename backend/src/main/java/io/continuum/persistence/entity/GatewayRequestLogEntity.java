package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Observability record for one gateway request — feeds the developer dashboard. */
@Entity
@Table(name = "gateway_requests",
        indexes = {
                @Index(name = "idx_gwreq_dev", columnList = "developer_id"),
                @Index(name = "idx_gwreq_created", columnList = "created_at")
        })
public class GatewayRequestLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "requested_model", length = 120)
    private String requestedModel;

    @Column(name = "chosen_provider", length = 40)
    private String chosenProvider;

    @Column(name = "chosen_model", length = 120)
    private String chosenModel;

    @Column(name = "complexity", nullable = false)
    private double complexity;

    @Column(name = "routing_reason", columnDefinition = "text")
    private String routingReason;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "tokens", nullable = false)
    private int tokens;

    @Column(name = "cost_usd", nullable = false)
    private double costUsd;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "failover_count", nullable = false)
    private int failoverCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected GatewayRequestLogEntity() {
    }

    public GatewayRequestLogEntity(String developerId, String requestedModel, String chosenProvider,
                                   String chosenModel, double complexity, String routingReason, long latencyMs,
                                   int tokens, double costUsd, boolean success, int failoverCount) {
        this.developerId = developerId;
        this.requestedModel = requestedModel;
        this.chosenProvider = chosenProvider;
        this.chosenModel = chosenModel;
        this.complexity = complexity;
        this.routingReason = routingReason;
        this.latencyMs = latencyMs;
        this.tokens = tokens;
        this.costUsd = costUsd;
        this.success = success;
        this.failoverCount = failoverCount;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getRequestedModel() { return requestedModel; }
    public String getChosenProvider() { return chosenProvider; }
    public String getChosenModel() { return chosenModel; }
    public double getComplexity() { return complexity; }
    public String getRoutingReason() { return routingReason; }
    public long getLatencyMs() { return latencyMs; }
    public int getTokens() { return tokens; }
    public double getCostUsd() { return costUsd; }
    public boolean isSuccess() { return success; }
    public int getFailoverCount() { return failoverCount; }
    public Instant getCreatedAt() { return createdAt; }
}
