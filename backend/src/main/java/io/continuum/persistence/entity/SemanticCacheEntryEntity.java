package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One cached gateway answer.
 *
 * <p>The prompt text is kept because matching is by meaning, not by hash — a
 * candidate has to be compared against the incoming prompt to decide whether it
 * is close enough to serve. The hash is only the exact-match fast path.
 */
@Entity
@Table(name = "semantic_cache_entry",
        indexes = {
                @Index(name = "idx_cache_entry_lookup", columnList = "developer_id, expires_at"),
                @Index(name = "idx_cache_entry_hash", columnList = "developer_id, prompt_hash")
        })
public class SemanticCacheEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "prompt_hash", nullable = false, length = 64)
    private String promptHash;

    @Column(name = "prompt_text", nullable = false, columnDefinition = "text")
    private String promptText;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "response", nullable = false, columnDefinition = "text")
    private String response;

    @Column(name = "provider", length = 64)
    private String provider;

    @Column(name = "tokens", nullable = false)
    private int tokens;

    @Column(name = "cost", nullable = false)
    private double cost;

    @Column(name = "hit_count", nullable = false)
    private int hitCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_hit_at")
    private Instant lastHitAt;

    protected SemanticCacheEntryEntity() {
    }

    public SemanticCacheEntryEntity(String developerId, String promptHash, String promptText, String model,
                                    String response, String provider, int tokens, double cost, Instant expiresAt) {
        this.developerId = developerId;
        this.promptHash = promptHash;
        this.promptText = promptText;
        this.model = model;
        this.response = response;
        this.provider = provider;
        this.tokens = tokens;
        this.cost = cost;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getPromptHash() { return promptHash; }
    public String getPromptText() { return promptText; }
    public String getModel() { return model; }
    public String getResponse() { return response; }
    public String getProvider() { return provider; }
    public int getTokens() { return tokens; }
    public double getCost() { return cost; }
    public int getHitCount() { return hitCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastHitAt() { return lastHitAt; }

    public void recordHit() {
        this.hitCount++;
        this.lastHitAt = Instant.now();
    }
}
