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

    /**
     * How this plan was arrived at: FREE_TIER, PAID, OPERATOR_GRANT, or
     * UNVERIFIED_LEGACY for rows that predate the payment check.
     */
    @Column(name = "plan_source", nullable = false, length = 24)
    private String planSource = "FREE_TIER";

    @Column(name = "payment_reference", length = 200)
    private String paymentReference;

    /** Which operator granted this plan, when it was granted rather than bought. */
    @Column(name = "granted_by", length = 120)
    private String grantedBy;

    @Column(name = "period_end")
    private Instant periodEnd;

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
    public String getPlanSource() { return planSource; }
    public void setPlanSource(String s) { this.planSource = s; this.updatedAt = Instant.now(); }
    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String r) { this.paymentReference = r; }
    public String getGrantedBy() { return grantedBy; }
    public void setGrantedBy(String g) { this.grantedBy = g; }
    public Instant getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(Instant e) { this.periodEnd = e; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
