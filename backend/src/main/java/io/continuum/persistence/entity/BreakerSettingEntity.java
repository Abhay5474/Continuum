package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Per-tenant circuit breaker configuration. OFF by default. */
@Entity
@Table(name = "breaker_setting")
public class BreakerSettingEntity {

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "warmup", nullable = false)
    private int warmup = 30;

    @Column(name = "slack", nullable = false)
    private double slack = 0.05;

    @Column(name = "threshold", nullable = false)
    private double threshold = 0.75;

    @Column(name = "cooldown_seconds", nullable = false)
    private int cooldownSeconds = 300;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected BreakerSettingEntity() {
    }

    public BreakerSettingEntity(String developerId) {
        this.developerId = developerId;
    }

    public String getDeveloperId() { return developerId; }
    public boolean isEnabled() { return enabled; }
    public int getWarmup() { return warmup; }
    public double getSlack() { return slack; }
    public double getThreshold() { return threshold; }
    public int getCooldownSeconds() { return cooldownSeconds; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean v) {
        this.enabled = v;
        this.updatedAt = Instant.now();
    }

    /**
     * Floored at ten. A baseline learned from fewer observations than this is
     * not a baseline, and the breaker would fire on ordinary variance.
     */
    public void setWarmup(int v) {
        this.warmup = Math.max(10, Math.min(500, v));
        this.updatedAt = Instant.now();
    }

    public void setSlack(double v) {
        this.slack = Math.max(0.0, Math.min(0.5, v));
        this.updatedAt = Instant.now();
    }

    public void setThreshold(double v) {
        this.threshold = Math.max(0.1, Math.min(10.0, v));
        this.updatedAt = Instant.now();
    }

    public void setCooldownSeconds(int v) {
        this.cooldownSeconds = Math.max(30, Math.min(86_400, v));
        this.updatedAt = Instant.now();
    }
}
