package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** An auditable Autopilot decision (observe/propose/canary/promote/rollback/skip). */
@Entity
@Table(name = "autopilot_decisions",
        indexes = @Index(name = "idx_apdecision_dev", columnList = "developer_id, created_at"))
public class AutopilotDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "type", nullable = false, length = 30)
    private String type;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "detail_json", columnDefinition = "text")
    private String detailJson;

    @Column(name = "bundle_id")
    private Long bundleId;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AutopilotDecisionEntity() {
    }

    public AutopilotDecisionEntity(String developerId, String type, String summary,
                                   String detailJson, Long bundleId, double confidence) {
        this.developerId = developerId;
        this.type = type;
        this.summary = summary;
        this.detailJson = detailJson;
        this.bundleId = bundleId;
        this.confidence = confidence;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getType() { return type; }
    public String getSummary() { return summary; }
    public String getDetailJson() { return detailJson; }
    public Long getBundleId() { return bundleId; }
    public double getConfidence() { return confidence; }
    public Instant getCreatedAt() { return createdAt; }
}
