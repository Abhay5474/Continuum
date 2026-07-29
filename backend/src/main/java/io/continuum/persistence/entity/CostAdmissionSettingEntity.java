package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Per-tenant cost-aware admission. OFF by default. */
@Entity
@Table(name = "cost_admission_setting")
public class CostAdmissionSettingEntity {

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** Sustained request allowance per minute. */
    @Column(name = "requests_per_min", nullable = false)
    private int requestsPerMin = 120;

    /** Sustained token allowance per minute — the resource that actually costs money. */
    @Column(name = "tokens_per_min", nullable = false)
    private int tokensPerMin = 120000;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CostAdmissionSettingEntity() {
    }

    public CostAdmissionSettingEntity(String developerId) {
        this.developerId = developerId;
    }

    public String getDeveloperId() { return developerId; }
    public boolean isEnabled() { return enabled; }
    public int getRequestsPerMin() { return requestsPerMin; }
    public int getTokensPerMin() { return tokensPerMin; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean v) { this.enabled = v; touch(); }
    public void setRequestsPerMin(int v) { this.requestsPerMin = Math.max(1, v); touch(); }
    public void setTokensPerMin(int v) { this.tokensPerMin = Math.max(1, v); touch(); }

    private void touch() { this.updatedAt = Instant.now(); }
}
