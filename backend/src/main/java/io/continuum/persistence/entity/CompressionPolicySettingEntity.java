package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Per-tenant adaptive compression policy. OFF by default.
 *
 * <p>Off means the compressor behaves exactly as it did before this existed: one
 * ratio for every message it touches.
 */
@Entity
@Table(name = "compression_policy_setting")
public class CompressionPolicySettingEntity {

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CompressionPolicySettingEntity() {
    }

    public CompressionPolicySettingEntity(String developerId) {
        this.developerId = developerId;
    }

    public String getDeveloperId() { return developerId; }
    public boolean isEnabled() { return enabled; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean v) { this.enabled = v; this.updatedAt = Instant.now(); }
}
