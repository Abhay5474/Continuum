package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Per-tenant quality gate configuration. OFF by default. */
@Entity
@Table(name = "quality_gate_setting")
public class QualityGateSettingEntity {

    public enum Mode {
        /** Never runs. */
        OFF,
        /**
         * Checks and records what it would have done, and changes nothing.
         * The only responsible way to introduce a gate that can rewrite answers.
         */
        MONITOR,
        /** Checks and repairs. */
        ENFORCE
    }

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private Mode mode = Mode.OFF;

    @Column(name = "threshold", nullable = false)
    private double threshold = 0.6;

    @Column(name = "max_repairs", nullable = false)
    private int maxRepairs = 1;

    @Column(name = "budget_ms", nullable = false)
    private int budgetMs = 4000;

    /**
     * The targeted repair engine. OFF by default; with it off the single-shot
     * repair behaves exactly as before.
     */
    @Column(name = "repair_engine_enabled", nullable = false)
    private boolean repairEngineEnabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected QualityGateSettingEntity() {
    }

    public QualityGateSettingEntity(String developerId) {
        this.developerId = developerId;
    }

    public String getDeveloperId() { return developerId; }
    public Mode getMode() { return mode; }
    public double getThreshold() { return threshold; }
    public int getMaxRepairs() { return maxRepairs; }
    public int getBudgetMs() { return budgetMs; }
    public boolean isRepairEngineEnabled() { return repairEngineEnabled; }

    public void setRepairEngineEnabled(boolean v) {
        this.repairEngineEnabled = v;
        this.updatedAt = Instant.now();
    }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setMode(Mode m) {
        this.mode = m == null ? Mode.OFF : m;
        this.updatedAt = Instant.now();
    }

    public void setThreshold(double v) {
        this.threshold = Math.max(0.0, Math.min(1.0, v));
        this.updatedAt = Instant.now();
    }

    /**
     * Capped at two. A gate that keeps re-asking is a gate that can spend
     * without bound on an answer it will never be satisfied with.
     */
    public void setMaxRepairs(int v) {
        this.maxRepairs = Math.max(0, Math.min(2, v));
        this.updatedAt = Instant.now();
    }

    public void setBudgetMs(int v) {
        this.budgetMs = Math.max(500, Math.min(20_000, v));
        this.updatedAt = Instant.now();
    }
}
