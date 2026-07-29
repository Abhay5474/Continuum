package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Per-tenant counterfactual evaluation. OFF by default.
 *
 * <p>It reads logs and changes nothing on the request path, but it is gated like
 * every other ranked feature so that turning it on is always a deliberate act.
 */
@Entity
@Table(name = "counterfactual_setting")
public class CounterfactualSettingEntity {

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CounterfactualSettingEntity() {
    }

    public CounterfactualSettingEntity(String developerId) {
        this.developerId = developerId;
    }

    public String getDeveloperId() { return developerId; }
    public boolean isEnabled() { return enabled; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean v) { this.enabled = v; this.updatedAt = Instant.now(); }
}
