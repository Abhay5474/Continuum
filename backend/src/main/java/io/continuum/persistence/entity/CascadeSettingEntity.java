package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Per-tenant cascade configuration. */
@Entity
@Table(name = "cascade_setting")
public class CascadeSettingEntity {

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "threshold", nullable = false)
    private double threshold = 0.75;

    @Column(name = "audit_rate", nullable = false)
    private double auditRate = 0.05;

    @Column(name = "escalation_cap", nullable = false)
    private double escalationCap = 0.60;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CascadeSettingEntity() {
    }

    public CascadeSettingEntity(String developerId) {
        this.developerId = developerId;
    }

    public String getDeveloperId() { return developerId; }
    public boolean isEnabled() { return enabled; }
    public double getThreshold() { return threshold; }
    public double getAuditRate() { return auditRate; }
    public double getEscalationCap() { return escalationCap; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean v) {
        this.enabled = v;
        this.updatedAt = Instant.now();
    }

    /**
     * Clamped rather than validated. The threshold is the only thing between
     * "saves money" and "ships worse answers", so a value outside the usable
     * band is corrected instead of stored.
     */
    public void setThreshold(double v) {
        this.threshold = Math.max(0.0, Math.min(1.0, v));
        this.updatedAt = Instant.now();
    }

    /** Auditing costs a second call, so it is capped well below half of traffic. */
    public void setAuditRate(double v) {
        this.auditRate = Math.max(0.0, Math.min(0.25, v));
        this.updatedAt = Instant.now();
    }

    public void setEscalationCap(double v) {
        this.escalationCap = Math.max(0.05, Math.min(1.0, v));
        this.updatedAt = Instant.now();
    }
}
