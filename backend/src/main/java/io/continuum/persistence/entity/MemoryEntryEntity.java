package io.continuum.persistence.entity;

import io.continuum.memory.MemoryTier;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * A single memory, stored OUTSIDE any model context window. Retrieval ranks
 * these by relevance/recency/salience and injects only the top few, mitigating
 * context overflow and lost-in-the-middle degradation for long-running agents.
 */
@Entity
@Table(name = "memory_entries",
        indexes = {
                @Index(name = "idx_memory_scope", columnList = "scope"),
                @Index(name = "idx_memory_tier", columnList = "scope, tier")
        })
public class MemoryEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Logical owner of the memory — typically an agent id or long-running workflow id. */
    @Column(name = "scope", nullable = false, length = 128)
    private String scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 20)
    private MemoryTier tier;

    @Column(name = "content", columnDefinition = "text", nullable = false)
    private String content;

    /** Optional compressed summary produced when the entry is archived. */
    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    /** Author-assigned importance in [0,1]. */
    @Column(name = "salience", nullable = false)
    private double salience;

    @Column(name = "access_count", nullable = false)
    private long accessCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;

    protected MemoryEntryEntity() {
    }

    public MemoryEntryEntity(String scope, MemoryTier tier, String content, double salience) {
        this.scope = scope;
        this.tier = tier;
        this.content = content;
        this.salience = Math.max(0, Math.min(1, salience));
        this.createdAt = Instant.now();
    }

    public void markAccessed() {
        this.accessCount++;
        this.lastAccessedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getScope() { return scope; }
    public MemoryTier getTier() { return tier; }
    public void setTier(MemoryTier tier) { this.tier = tier; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public double getSalience() { return salience; }
    public void setSalience(double salience) { this.salience = salience; }
    public long getAccessCount() { return accessCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastAccessedAt() { return lastAccessedAt; }
}
