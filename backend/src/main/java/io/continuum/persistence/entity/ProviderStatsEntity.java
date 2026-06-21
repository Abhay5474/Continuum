package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Rolling, persisted runtime statistics per provider. These are <em>measured</em>
 * from real calls (latency, tokens, cost, success/failure) and are the only
 * inputs the model router scores on — there are no hardcoded provider rankings.
 */
@Entity
@Table(name = "provider_stats")
public class ProviderStatsEntity {

    @Id
    @Column(name = "provider", length = 40)
    private String provider;

    @Column(name = "calls", nullable = false)
    private long calls;

    @Column(name = "successes", nullable = false)
    private long successes;

    @Column(name = "failures", nullable = false)
    private long failures;

    @Column(name = "total_latency_ms", nullable = false)
    private long totalLatencyMs;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "total_cost_usd", nullable = false)
    private double totalCostUsd;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ProviderStatsEntity() {
    }

    public ProviderStatsEntity(String provider) {
        this.provider = provider;
        this.updatedAt = Instant.now();
    }

    public void recordSuccess(long latencyMs, int promptTokens, int completionTokens, double cost) {
        this.calls++;
        this.successes++;
        this.totalLatencyMs += latencyMs;
        this.promptTokens += promptTokens;
        this.completionTokens += completionTokens;
        this.totalCostUsd += cost;
        this.updatedAt = Instant.now();
    }

    public void recordFailure(long latencyMs) {
        this.calls++;
        this.failures++;
        this.totalLatencyMs += latencyMs;
        this.updatedAt = Instant.now();
    }

    public String getProvider() { return provider; }
    public long getCalls() { return calls; }
    public long getSuccesses() { return successes; }
    public long getFailures() { return failures; }
    public long getTotalLatencyMs() { return totalLatencyMs; }
    public long getPromptTokens() { return promptTokens; }
    public long getCompletionTokens() { return completionTokens; }
    public double getTotalCostUsd() { return totalCostUsd; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Transient
    public double getErrorRate() {
        return calls == 0 ? 0.0 : (double) failures / calls;
    }

    @Transient
    public double getAvgLatencyMs() {
        return calls == 0 ? 0.0 : (double) totalLatencyMs / calls;
    }

    @Transient
    public double getAvgCostPerCall() {
        return successes == 0 ? 0.0 : totalCostUsd / successes;
    }

    @Transient
    public double getAvgTokensPerCall() {
        return successes == 0 ? 0.0 : (double) (promptTokens + completionTokens) / successes;
    }
}
