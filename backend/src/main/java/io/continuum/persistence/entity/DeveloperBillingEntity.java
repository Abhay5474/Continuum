package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Per-developer billing plan + monthly token quota (usage derived from gateway_requests). */
@Entity
@Table(name = "developer_billing")
public class DeveloperBillingEntity {

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "plan", nullable = false, length = 16)
    private String plan = "FREE";

    @Column(name = "monthly_token_quota", nullable = false)
    private long monthlyTokenQuota = 100_000;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected DeveloperBillingEntity() {
    }

    public DeveloperBillingEntity(String developerId) {
        this.developerId = developerId;
    }

    public String getDeveloperId() { return developerId; }
    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; this.updatedAt = Instant.now(); }
    public long getMonthlyTokenQuota() { return monthlyTokenQuota; }
    public void setMonthlyTokenQuota(long q) { this.monthlyTokenQuota = q; this.updatedAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
