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
}
