package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Measured health of a specific (provider, model) pair, used for model-level fallback ordering. */
@Entity
@Table(name = "provider_model_health")
public class ProviderModelHealthEntity {

    /** Composite natural key encoded as "provider:model". */
    @Id
    @Column(name = "id", length = 180)
    private String id;

    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    @Column(name = "model_name", nullable = false, length = 120)
    private String modelName;

    @Column(name = "calls", nullable = false)
    private long calls;

    @Column(name = "failures", nullable = false)
    private long failures;

    @Column(name = "total_latency_ms", nullable = false)
    private long totalLatencyMs;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ProviderModelHealthEntity() {
    }

    public ProviderModelHealthEntity(String provider, String modelName) {
        this.id = provider + ":" + modelName;
        this.provider = provider;
        this.modelName = modelName;
    }

    public static String key(String provider, String model) {
        return provider + ":" + model;
    }

    public void recordSuccess(long latencyMs) {
        calls++;
        totalLatencyMs += latencyMs;
        updatedAt = Instant.now();
    }

    public void recordFailure(long latencyMs, String error) {
        calls++;
        failures++;
        totalLatencyMs += latencyMs;
        lastError = error;
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProvider() { return provider; }
    public String getModelName() { return modelName; }
    public long getCalls() { return calls; }
    public long getFailures() { return failures; }
    public long getTotalLatencyMs() { return totalLatencyMs; }
    public String getLastError() { return lastError; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Transient
    public double getHealthScore() {
        return calls == 0 ? 1.0 : 1.0 - (double) failures / calls;
    }

    @Transient
    public double getAvgLatencyMs() {
        return calls == 0 ? 0.0 : (double) totalLatencyMs / calls;
    }
}
