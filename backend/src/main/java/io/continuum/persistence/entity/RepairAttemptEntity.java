package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One repair attempt, kept or discarded.
 *
 * <p>The discarded ones matter as much as the kept ones: they are the evidence
 * that the guard against making an answer worse is doing something, and the only
 * way to tell a repair engine that helps from one that is expensively churning.
 */
@Entity
@Table(name = "repair_attempt",
        indexes = @Index(name = "idx_repair_attempt_dev", columnList = "developer_id,created_at"))
public class RepairAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "model", length = 120)
    private String model;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "strategy", nullable = false, length = 32)
    private String strategy;

    @Column(name = "defects", length = 1000)
    private String defects;

    @Column(name = "score_before", nullable = false)
    private double scoreBefore;

    @Column(name = "score_after", nullable = false)
    private double scoreAfter;

    @Column(name = "kept", nullable = false)
    private boolean kept;

    @Column(name = "note", length = 300)
    private String note;

    @Column(name = "cost", nullable = false)
    private double cost;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected RepairAttemptEntity() {
    }

    public RepairAttemptEntity(String developerId, String model, int attempt, String strategy,
                               String defects, double scoreBefore, double scoreAfter, boolean kept,
                               String note, double cost, long latencyMs) {
        this.developerId = developerId;
        this.model = model;
        this.attempt = attempt;
        this.strategy = strategy;
        this.defects = defects == null ? null : defects.substring(0, Math.min(1000, defects.length()));
        this.scoreBefore = scoreBefore;
        this.scoreAfter = scoreAfter;
        this.kept = kept;
        this.note = note == null ? null : note.substring(0, Math.min(300, note.length()));
        this.cost = cost;
        this.latencyMs = latencyMs;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getModel() { return model; }
    public int getAttempt() { return attempt; }
    public String getStrategy() { return strategy; }
    public String getDefects() { return defects; }
    public double getScoreBefore() { return scoreBefore; }
    public double getScoreAfter() { return scoreAfter; }
    public boolean isKept() { return kept; }
    public String getNote() { return note; }
    public double getCost() { return cost; }
    public long getLatencyMs() { return latencyMs; }
    public Instant getCreatedAt() { return createdAt; }
}
