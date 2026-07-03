package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Attributes a gateway request to the policy bundle that served it (and whether
 * it was a canary), so canary metrics can be compared per bundle WITHOUT
 * altering the existing {@code gateway_requests} table.
 */
@Entity
@Table(name = "autopilot_request_labels",
        indexes = @Index(name = "idx_aplabel_bundle", columnList = "bundle_id"))
public class AutopilotRequestLabelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "gateway_request_id")
    private Long gatewayRequestId;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "bundle_id", nullable = false)
    private Long bundleId;

    @Column(name = "is_canary", nullable = false)
    private boolean canary;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "cost_usd", nullable = false)
    private double costUsd;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AutopilotRequestLabelEntity() {
    }

    public AutopilotRequestLabelEntity(Long gatewayRequestId, String developerId, Long bundleId,
                                       boolean canary, boolean success, long latencyMs, double costUsd) {
        this.gatewayRequestId = gatewayRequestId;
        this.developerId = developerId;
        this.bundleId = bundleId;
        this.canary = canary;
        this.success = success;
        this.latencyMs = latencyMs;
        this.costUsd = costUsd;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getGatewayRequestId() { return gatewayRequestId; }
    public String getDeveloperId() { return developerId; }
    public Long getBundleId() { return bundleId; }
    public boolean isCanary() { return canary; }
    public boolean isSuccess() { return success; }
    public long getLatencyMs() { return latencyMs; }
    public double getCostUsd() { return costUsd; }
    public Instant getCreatedAt() { return createdAt; }
}
