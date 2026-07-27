package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One cascade decision.
 *
 * <p>{@code agreedWithStrong} is the point of the row. Whenever both tiers ran —
 * because the judge escalated, or because the request fell into the audit slice
 * — the two answers are compared. Agreement means the cheap answer was fine and
 * the escalation was wasted; disagreement means it was earned. That is a label
 * obtained without a human, a benchmark or any ground truth, and it is what
 * makes the threshold tunable from real traffic.
 */
@Entity
@Table(name = "cascade_decision",
        indexes = @Index(name = "idx_cascade_decision_dev", columnList = "developer_id, created_at"))
public class CascadeDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "complexity", nullable = false)
    private double complexity;

    @Column(name = "raw_score", nullable = false)
    private double rawScore;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "threshold", nullable = false)
    private double threshold;

    @Column(name = "escalated", nullable = false)
    private boolean escalated;

    @Column(name = "audit", nullable = false)
    private boolean audit;

    @Column(name = "agreed_with_strong")
    private Boolean agreedWithStrong;

    @Column(name = "similarity")
    private Double similarity;

    @Column(name = "concerns", columnDefinition = "text")
    private String concerns;

    @Column(name = "cheap_model", length = 128)
    private String cheapModel;

    @Column(name = "strong_model", length = 128)
    private String strongModel;

    @Column(name = "cheap_cost", nullable = false)
    private double cheapCost;

    @Column(name = "strong_cost", nullable = false)
    private double strongCost;

    @Column(name = "cheap_latency_ms", nullable = false)
    private long cheapLatencyMs;

    @Column(name = "total_latency_ms", nullable = false)
    private long totalLatencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected CascadeDecisionEntity() {
    }

    public CascadeDecisionEntity(String developerId, double complexity, double rawScore, double confidence,
                                 double threshold, boolean escalated, boolean audit, Boolean agreedWithStrong,
                                 Double similarity, String concerns, String cheapModel, String strongModel,
                                 double cheapCost, double strongCost, long cheapLatencyMs, long totalLatencyMs) {
        this.developerId = developerId;
        this.complexity = complexity;
        this.rawScore = rawScore;
        this.confidence = confidence;
        this.threshold = threshold;
        this.escalated = escalated;
        this.audit = audit;
        this.agreedWithStrong = agreedWithStrong;
        this.similarity = similarity;
        this.concerns = concerns;
        this.cheapModel = cheapModel;
        this.strongModel = strongModel;
        this.cheapCost = cheapCost;
        this.strongCost = strongCost;
        this.cheapLatencyMs = cheapLatencyMs;
        this.totalLatencyMs = totalLatencyMs;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public double getComplexity() { return complexity; }
    public double getRawScore() { return rawScore; }
    public double getConfidence() { return confidence; }
    public double getThreshold() { return threshold; }
    public boolean isEscalated() { return escalated; }
    public boolean isAudit() { return audit; }
    public Boolean getAgreedWithStrong() { return agreedWithStrong; }
    public Double getSimilarity() { return similarity; }
    public String getConcerns() { return concerns; }
    public String getCheapModel() { return cheapModel; }
    public String getStrongModel() { return strongModel; }
    public double getCheapCost() { return cheapCost; }
    public double getStrongCost() { return strongCost; }
    public long getCheapLatencyMs() { return cheapLatencyMs; }
    public long getTotalLatencyMs() { return totalLatencyMs; }
    public Instant getCreatedAt() { return createdAt; }
}
