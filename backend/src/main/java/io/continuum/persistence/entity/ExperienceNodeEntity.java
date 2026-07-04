package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Tier 3 — one node of the semantic experience graph (skill / lesson / fact). */
@Entity
@Table(name = "memory_experience_nodes", indexes = {
        @Index(name = "idx_mem_nodes_dev", columnList = "developer_id"),
        @Index(name = "idx_mem_nodes_exp", columnList = "expires_at")})
public class ExperienceNodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "text", nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "embedding_json", columnDefinition = "text")
    private String embeddingJson;

    @Column(name = "utility_score", nullable = false)
    private double utilityScore = 0.5;

    @Column(name = "uses", nullable = false)
    private long uses = 0;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ExperienceNodeEntity() {
    }

    public ExperienceNodeEntity(String developerId, String kind, String text,
                                String embeddingJson, double utilityScore, Instant expiresAt) {
        this.developerId = developerId;
        this.kind = kind;
        this.text = text;
        this.embeddingJson = embeddingJson;
        this.utilityScore = utilityScore;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getKind() { return kind; }
    public String getText() { return text; }
    public String getEmbeddingJson() { return embeddingJson; }
    public double getUtilityScore() { return utilityScore; }
    public void setUtilityScore(double v) { this.utilityScore = v; }
    public long getUses() { return uses; }
    public void markUsed() { this.uses++; this.lastUsedAt = Instant.now(); }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getCreatedAt() { return createdAt; }
}
