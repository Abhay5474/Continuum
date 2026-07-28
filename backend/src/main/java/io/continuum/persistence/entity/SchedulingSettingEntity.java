package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Per-tenant priority and deadline scheduling. OFF by default. */
@Entity
@Table(name = "scheduling_setting")
public class SchedulingSettingEntity {

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SchedulingSettingEntity() {
    }

    public SchedulingSettingEntity(String developerId) {
        this.developerId = developerId;
    }

    public String getDeveloperId() { return developerId; }
    public boolean isEnabled() { return enabled; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean v) { this.enabled = v; this.updatedAt = Instant.now(); }
}
