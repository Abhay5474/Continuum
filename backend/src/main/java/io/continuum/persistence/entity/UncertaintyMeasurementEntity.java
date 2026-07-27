package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One uncertainty measurement.
 *
 * <p>The clusters are stored, not just the number. A confidence of 0.19 is not
 * actionable on its own; the three incompatible answers that produced it are.
 */
@Entity
@Table(name = "uncertainty_measurement",
        indexes = @Index(name = "idx_uncertainty_dev", columnList = "developer_id, created_at"))
public class UncertaintyMeasurementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "prompt", columnDefinition = "text")
    private String prompt;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "samples", nullable = false)
    private int samples;

    @Column(name = "clusters", nullable = false)
    private int clusters;

    @Column(name = "entropy", nullable = false)
    private double entropy;

    @Column(name = "normalised", nullable = false)
    private double normalised;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "clusters_json", columnDefinition = "text")
    private String clustersJson;

    @Column(name = "extra_cost", nullable = false)
    private double extraCost;

    @Column(name = "extra_ms", nullable = false)
    private long extraMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected UncertaintyMeasurementEntity() {
    }

    public UncertaintyMeasurementEntity(String developerId, String prompt, String model, int samples,
                                        int clusters, double entropy, double normalised, double confidence,
                                        String clustersJson, double extraCost, long extraMs) {
        this.developerId = developerId;
        this.prompt = prompt;
        this.model = model;
        this.samples = samples;
        this.clusters = clusters;
        this.entropy = entropy;
        this.normalised = normalised;
        this.confidence = confidence;
        this.clustersJson = clustersJson;
        this.extraCost = extraCost;
        this.extraMs = extraMs;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getPrompt() { return prompt; }
    public String getModel() { return model; }
    public int getSamples() { return samples; }
    public int getClusters() { return clusters; }
    public double getEntropy() { return entropy; }
    public double getNormalised() { return normalised; }
    public double getConfidence() { return confidence; }
    public String getClustersJson() { return clustersJson; }
    public double getExtraCost() { return extraCost; }
    public long getExtraMs() { return extraMs; }
    public Instant getCreatedAt() { return createdAt; }
}
