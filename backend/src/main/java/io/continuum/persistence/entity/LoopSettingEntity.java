package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Per-tenant loop detection. OFF by default. */
@Entity
@Table(name = "loop_setting")
public class LoopSettingEntity {

    /** MONITOR records what it would have done; HALT also stops the run. */
    public enum Mode { MONITOR, HALT }

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private Mode mode = Mode.MONITOR;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected LoopSettingEntity() {
    }

    public LoopSettingEntity(String developerId) {
        this.developerId = developerId;
    }

    public String getDeveloperId() { return developerId; }
    public boolean isEnabled() { return enabled; }
    public Mode getMode() { return mode; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean v) { this.enabled = v; this.updatedAt = Instant.now(); }
    public void setMode(Mode m) { this.mode = m == null ? Mode.MONITOR : m; this.updatedAt = Instant.now(); }
}
