package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Per-tenant admission control configuration. OFF by default.
 *
 * <p>There is only one setting. The whole point of gradient-based admission is
 * that the concurrency limit is inferred rather than typed, so exposing a
 * "maximum concurrency" field would invite exactly the guess the feature exists
 * to replace.
 */
@Entity
@Table(name = "admission_setting")
public class AdmissionSettingEntity {

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AdmissionSettingEntity() {
    }

    public AdmissionSettingEntity(String developerId) {
        this.developerId = developerId;
    }

    public String getDeveloperId() { return developerId; }
    public boolean isEnabled() { return enabled; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean v) {
        this.enabled = v;
        this.updatedAt = Instant.now();
    }
}
