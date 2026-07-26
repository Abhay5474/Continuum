package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Per-tenant cache configuration and running totals. */
@Entity
@Table(name = "semantic_cache_setting")
public class SemanticCacheSettingEntity {

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "similarity_threshold", nullable = false)
    private double similarityThreshold = 0.92;

    @Column(name = "ttl_seconds", nullable = false)
    private int ttlSeconds = 86_400;

    @Column(name = "hits", nullable = false)
    private long hits;

    @Column(name = "misses", nullable = false)
    private long misses;

    @Column(name = "tokens_saved", nullable = false)
    private long tokensSaved;

    @Column(name = "cost_saved", nullable = false)
    private double costSaved;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SemanticCacheSettingEntity() {
    }

    public SemanticCacheSettingEntity(String developerId) {
        this.developerId = developerId;
    }

    public String getDeveloperId() { return developerId; }
    public boolean isEnabled() { return enabled; }
    public double getSimilarityThreshold() { return similarityThreshold; }
    public int getTtlSeconds() { return ttlSeconds; }
    public long getHits() { return hits; }
    public long getMisses() { return misses; }
    public long getTokensSaved() { return tokensSaved; }
    public double getCostSaved() { return costSaved; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    /**
     * Clamped rather than validated: the threshold is the only thing standing
     * between "saves money" and "answers the wrong question", so a value outside
     * the sane band is corrected instead of stored.
     */
    public void setSimilarityThreshold(double v) {
        this.similarityThreshold = Math.max(0.5, Math.min(1.0, v));
        this.updatedAt = Instant.now();
    }

    public void setTtlSeconds(int v) {
        this.ttlSeconds = Math.max(60, Math.min(2_592_000, v));
        this.updatedAt = Instant.now();
    }

    public void recordHit(int tokens, double cost) {
        this.hits++;
        this.tokensSaved += Math.max(0, tokens);
        this.costSaved += Math.max(0, cost);
    }

    public void recordMiss() {
        this.misses++;
    }

    public void resetStats() {
        this.hits = 0;
        this.misses = 0;
        this.tokensSaved = 0;
        this.costSaved = 0;
        this.updatedAt = Instant.now();
    }
}
