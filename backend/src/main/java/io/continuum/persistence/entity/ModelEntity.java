package io.continuum.persistence.entity;

import io.continuum.registry.ModelStatus;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * Registry record for a provider model, including lifecycle status, context
 * window, capabilities and pricing (capabilities/pricing stored as JSON).
 */
@Entity
@Table(name = "models",
        uniqueConstraints = @UniqueConstraint(name = "uq_model", columnNames = {"provider", "model_name"}),
        indexes = @Index(name = "idx_models_status", columnList = "status"))
public class ModelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    @Column(name = "model_name", nullable = false, length = 120)
    private String modelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ModelStatus status;

    @Column(name = "context_window", nullable = false)
    private int contextWindow;

    @Column(name = "capabilities_json", columnDefinition = "text")
    private String capabilitiesJson;

    @Column(name = "pricing_json", columnDefinition = "text")
    private String pricingJson;

    @Column(name = "last_checked_at", nullable = false)
    private Instant lastCheckedAt = Instant.now();

    // ---- catalogue facts (V51) ----------------------------------------------

    /** What the model is for; see {@code ModelKind}. Null on rows from before the catalogue: read as chat. */
    @Column(name = "kind", length = 24)
    private String kind;

    @Column(name = "display_name", length = 160)
    private String displayName;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /** Why a model is not routed even though it is listed (speech model, alias…). */
    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "max_output_tokens", nullable = false)
    private int maxOutputTokens;

    @Column(name = "provider_created_at", nullable = false)
    private long providerCreatedAt;

    @Column(name = "preview", nullable = false)
    private boolean preview;

    /** The latest reason for the current status, in words. */
    @Column(name = "status_reason", columnDefinition = "text")
    private String statusReason;

    /** When a test call last succeeded. */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "probe_outcome", length = 24)
    private String probeOutcome;

    @Column(name = "probed_at")
    private Instant probedAt;

    /** Limits the provider reported for this key and model, as JSON. */
    @Column(name = "limits_json", columnDefinition = "text")
    private String limitsJson;

    /** Consecutive successful lists this model was absent from. */
    @Column(name = "miss_count", nullable = false)
    private int missCount;

    @Column(name = "missing_since")
    private Instant missingSince;

    @Column(name = "retired_at")
    private Instant retiredAt;

    /** What requests naming this model are sent to once it is retired. */
    @Column(name = "replaced_by", length = 120)
    private String replacedBy;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt = Instant.now();

    /** "live" once the provider's own list has named it; "seed" before that. */
    @Column(name = "source", nullable = false, length = 16)
    private String source = "seed";

    protected ModelEntity() {
    }

    public ModelEntity(String provider, String modelName, ModelStatus status, int contextWindow,
                       String capabilitiesJson, String pricingJson) {
        this.provider = provider;
        this.modelName = modelName;
        this.status = status;
        this.contextWindow = contextWindow;
        this.capabilitiesJson = capabilitiesJson;
        this.pricingJson = pricingJson;
        this.lastCheckedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getProvider() { return provider; }
    public String getModelName() { return modelName; }
    public ModelStatus getStatus() { return status; }
    public void setStatus(ModelStatus status) { this.status = status; }
    public int getContextWindow() { return contextWindow; }
    public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }
    public String getCapabilitiesJson() { return capabilitiesJson; }
    public void setCapabilitiesJson(String capabilitiesJson) { this.capabilitiesJson = capabilitiesJson; }
    public String getPricingJson() { return pricingJson; }
    public void setPricingJson(String pricingJson) { this.pricingJson = pricingJson; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void markChecked() { this.lastCheckedAt = Instant.now(); }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public long getProviderCreatedAt() { return providerCreatedAt; }
    public void setProviderCreatedAt(long providerCreatedAt) { this.providerCreatedAt = providerCreatedAt; }
    public boolean isPreview() { return preview; }
    public void setPreview(boolean preview) { this.preview = preview; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
    public String getProbeOutcome() { return probeOutcome; }
    public void setProbeOutcome(String probeOutcome) { this.probeOutcome = probeOutcome; }
    public Instant getProbedAt() { return probedAt; }
    public void setProbedAt(Instant probedAt) { this.probedAt = probedAt; }
    public String getLimitsJson() { return limitsJson; }
    public void setLimitsJson(String limitsJson) { this.limitsJson = limitsJson; }
    public int getMissCount() { return missCount; }
    public void setMissCount(int missCount) { this.missCount = missCount; }
    public Instant getMissingSince() { return missingSince; }
    public void setMissingSince(Instant missingSince) { this.missingSince = missingSince; }
    public Instant getRetiredAt() { return retiredAt; }
    public void setRetiredAt(Instant retiredAt) { this.retiredAt = retiredAt; }
    public String getReplacedBy() { return replacedBy; }
    public void setReplacedBy(String replacedBy) { this.replacedBy = replacedBy; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    /** Chat models are the only ones routed; rows from before the catalogue have no kind and are chat. */
    public boolean isChat() { return kind == null || "CHAT".equals(kind); }
}
