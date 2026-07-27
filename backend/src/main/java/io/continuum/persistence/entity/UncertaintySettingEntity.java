package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Per-tenant uncertainty configuration. */
@Entity
@Table(name = "uncertainty_setting")
public class UncertaintySettingEntity {

    /** When uncertainty is measured. Cost scales with how often, so this matters. */
    public enum Mode {
        /** Never. */
        OFF,
        /** Only when the request explicitly asks for it. */
        ON_DEMAND,
        /** Only when the cascade judge was unsure — the cheap targeting rule. */
        ADAPTIVE,
        /** Every request. Honest, and k times the bill. */
        ALWAYS
    }

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private Mode mode = Mode.OFF;

    @Column(name = "samples", nullable = false)
    private int samples = 3;

    @Column(name = "temperature", nullable = false)
    private double temperature = 0.7;

    @Column(name = "low_confidence", nullable = false)
    private double lowConfidence = 0.5;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected UncertaintySettingEntity() {
    }

    public UncertaintySettingEntity(String developerId) {
        this.developerId = developerId;
    }

    public String getDeveloperId() { return developerId; }
    public Mode getMode() { return mode; }
    public int getSamples() { return samples; }
    public double getTemperature() { return temperature; }
    public double getLowConfidence() { return lowConfidence; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setMode(Mode m) {
        this.mode = m == null ? Mode.OFF : m;
        this.updatedAt = Instant.now();
    }

    /**
     * Clamped to 2–7. One sample measures nothing; beyond seven the marginal
     * information is small and the bill is not.
     */
    public void setSamples(int v) {
        this.samples = Math.max(2, Math.min(7, v));
        this.updatedAt = Instant.now();
    }

    /**
     * Floored above zero. At temperature 0 every sample is identical, entropy is
     * always 0, and the measurement would confidently report certainty about
     * everything — the worst possible failure for this feature.
     */
    public void setTemperature(double v) {
        this.temperature = Math.max(0.2, Math.min(1.5, v));
        this.updatedAt = Instant.now();
    }

    public void setLowConfidence(double v) {
        this.lowConfidence = Math.max(0.0, Math.min(1.0, v));
        this.updatedAt = Instant.now();
    }
}
