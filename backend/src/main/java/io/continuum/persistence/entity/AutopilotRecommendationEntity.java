package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** A human-readable, developer-approvable proposal (read-only recommendations mode). */
@Entity
@Table(name = "autopilot_recommendations",
        indexes = @Index(name = "idx_aprec_dev", columnList = "developer_id, status"))
public class AutopilotRecommendationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "rationale", columnDefinition = "text")
    private String rationale;

    @Column(name = "impact", length = 200)
    private String impact;

    @Column(name = "proposed_bundle_id")
    private Long proposedBundleId;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING"; // PENDING | ACCEPTED | REJECTED | SUPERSEDED

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AutopilotRecommendationEntity() {
    }

    public AutopilotRecommendationEntity(String developerId, String title, String rationale, String impact,
                                         Long proposedBundleId, double confidence) {
        this.developerId = developerId;
        this.title = title;
        this.rationale = rationale;
        this.impact = impact;
        this.proposedBundleId = proposedBundleId;
        this.confidence = confidence;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getTitle() { return title; }
    public String getRationale() { return rationale; }
    public String getImpact() { return impact; }
    public Long getProposedBundleId() { return proposedBundleId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public double getConfidence() { return confidence; }
    public Instant getCreatedAt() { return createdAt; }
}
