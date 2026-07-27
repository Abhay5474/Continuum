package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One gate check.
 *
 * <p>{@code action} is what the gate decided; {@code applied} is what actually
 * happened. In MONITOR they differ on every failing answer, and that gap is the
 * evidence for whether enforcing would be an improvement or a liability.
 */
@Entity
@Table(name = "quality_gate_check",
        indexes = @Index(name = "idx_quality_check_dev", columnList = "developer_id, created_at"))
public class QualityGateCheckEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "threshold", nullable = false)
    private double threshold;

    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "applied", nullable = false, length = 16)
    private String applied;

    @Column(name = "defects", columnDefinition = "text")
    private String defects;

    @Column(name = "dimensions_json", columnDefinition = "text")
    private String dimensionsJson;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "original_answer", columnDefinition = "text")
    private String originalAnswer;

    @Column(name = "repaired_answer", columnDefinition = "text")
    private String repairedAnswer;

    @Column(name = "repair_improved")
    private Boolean repairImproved;

    @Column(name = "repair_score")
    private Double repairScore;

    @Column(name = "extra_cost", nullable = false)
    private double extraCost;

    @Column(name = "extra_ms", nullable = false)
    private long extraMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected QualityGateCheckEntity() {
    }

    public QualityGateCheckEntity(String developerId, String mode, double score, double threshold,
                                  String action, String applied, String defects, String dimensionsJson,
                                  String model, String originalAnswer, String repairedAnswer,
                                  Boolean repairImproved, Double repairScore,
                                  double extraCost, long extraMs) {
        this.developerId = developerId;
        this.mode = mode;
        this.score = score;
        this.threshold = threshold;
        this.action = action;
        this.applied = applied;
        this.defects = defects;
        this.dimensionsJson = dimensionsJson;
        this.model = model;
        this.originalAnswer = originalAnswer;
        this.repairedAnswer = repairedAnswer;
        this.repairImproved = repairImproved;
        this.repairScore = repairScore;
        this.extraCost = extraCost;
        this.extraMs = extraMs;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getMode() { return mode; }
    public double getScore() { return score; }
    public double getThreshold() { return threshold; }
    public String getAction() { return action; }
    public String getApplied() { return applied; }
    public String getDefects() { return defects; }
    public String getDimensionsJson() { return dimensionsJson; }
    public String getModel() { return model; }
    public String getOriginalAnswer() { return originalAnswer; }
    public String getRepairedAnswer() { return repairedAnswer; }
    public Boolean getRepairImproved() { return repairImproved; }
    public Double getRepairScore() { return repairScore; }
    public double getExtraCost() { return extraCost; }
    public long getExtraMs() { return extraMs; }
    public Instant getCreatedAt() { return createdAt; }
}
