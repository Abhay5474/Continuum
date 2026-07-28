package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Per-tenant saga compensation. OFF by default. */
@Entity
@Table(name = "saga_setting")
public class SagaSettingEntity {

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SagaSettingEntity() {
    }

    public SagaSettingEntity(String developerId) {
        this.developerId = developerId;
    }

    public String getDeveloperId() { return developerId; }
    public boolean isEnabled() { return enabled; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean v) { this.enabled = v; this.updatedAt = Instant.now(); }
}
