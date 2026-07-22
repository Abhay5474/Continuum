package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Telemetry for one compressed request (before/after tokens = the research metric). */
@Entity
@Table(name = "compression_metrics",
        indexes = @Index(name = "idx_compression_dev", columnList = "developer_id, created_at"))
public class CompressionMetricEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "original_tokens", nullable = false)
    private int originalTokens;

    @Column(name = "compressed_tokens", nullable = false)
    private int compressedTokens;

    @Column(name = "target_ratio", nullable = false)
    private double targetRatio;

    @Column(name = "achieved_ratio", nullable = false)
    private double achievedRatio;

    @Column(name = "protected_spans", nullable = false)
    private int protectedSpans;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected CompressionMetricEntity() {
    }

    public CompressionMetricEntity(String developerId, int originalTokens, int compressedTokens,
                                   double targetRatio, double achievedRatio, int protectedSpans) {
        this.developerId = developerId;
        this.originalTokens = originalTokens;
        this.compressedTokens = compressedTokens;
        this.targetRatio = targetRatio;
        this.achievedRatio = achievedRatio;
        this.protectedSpans = protectedSpans;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public int getOriginalTokens() { return originalTokens; }
    public int getCompressedTokens() { return compressedTokens; }
    public double getTargetRatio() { return targetRatio; }
    public double getAchievedRatio() { return achievedRatio; }
    public int getProtectedSpans() { return protectedSpans; }
    public Instant getCreatedAt() { return createdAt; }
}
