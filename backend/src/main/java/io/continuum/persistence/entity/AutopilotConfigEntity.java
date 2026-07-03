package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Per-developer Autopilot opt-in state and profile. Default is DISABLED — when
 * disabled the gateway behaves exactly as before and no learning occurs.
 */
@Entity
@Table(name = "autopilot_config")
public class AutopilotConfigEntity {

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    /** When true Autopilot may auto-canary+promote; when false it only recommends. */
    @Column(name = "auto_apply", nullable = false)
    private boolean autoApply = false;

    @Column(name = "mode", nullable = false, length = 20)
    private String mode = "BALANCED";

    @Column(name = "profile_json", columnDefinition = "text")
    private String profileJson;

    @Column(name = "active_bundle_id")
    private Long activeBundleId;

    @Column(name = "canary_bundle_id")
    private Long canaryBundleId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AutopilotConfigEntity() {
    }

    public AutopilotConfigEntity(String developerId) {
        this.developerId = developerId;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public String getDeveloperId() { return developerId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; touch(); }
    public boolean isAutoApply() { return autoApply; }
    public void setAutoApply(boolean autoApply) { this.autoApply = autoApply; touch(); }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; touch(); }
    public String getProfileJson() { return profileJson; }
    public void setProfileJson(String profileJson) { this.profileJson = profileJson; touch(); }
    public Long getActiveBundleId() { return activeBundleId; }
    public void setActiveBundleId(Long id) { this.activeBundleId = id; touch(); }
    public Long getCanaryBundleId() { return canaryBundleId; }
    public void setCanaryBundleId(Long id) { this.canaryBundleId = id; touch(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
