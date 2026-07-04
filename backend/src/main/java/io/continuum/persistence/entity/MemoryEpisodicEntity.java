package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Tier 2 — episodic summaries produced by consolidating working memory. */
@Entity
@Table(name = "memory_episodic", indexes = {
        @Index(name = "idx_mem_episodic_dev", columnList = "developer_id"),
        @Index(name = "idx_mem_episodic_exp", columnList = "expires_at")})
public class MemoryEpisodicEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "summary_text", nullable = false, columnDefinition = "text")
    private String summaryText;

    @Column(name = "source_items", nullable = false)
    private int sourceItems;

    @Column(name = "source_tokens", nullable = false)
    private int sourceTokens;

    @Column(name = "summary_tokens", nullable = false)
    private int summaryTokens;

    @Column(name = "embedding_json", columnDefinition = "text")
    private String embeddingJson;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected MemoryEpisodicEntity() {
    }

    public MemoryEpisodicEntity(String developerId, String sessionId, String summaryText,
                                int sourceItems, int sourceTokens, int summaryTokens,
                                String embeddingJson, Instant expiresAt) {
        this.developerId = developerId;
        this.sessionId = sessionId;
        this.summaryText = summaryText;
        this.sourceItems = sourceItems;
        this.sourceTokens = sourceTokens;
        this.summaryTokens = summaryTokens;
        this.embeddingJson = embeddingJson;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getSessionId() { return sessionId; }
    public String getSummaryText() { return summaryText; }
    public int getSourceItems() { return sourceItems; }
    public int getSourceTokens() { return sourceTokens; }
    public int getSummaryTokens() { return summaryTokens; }
    public String getEmbeddingJson() { return embeddingJson; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}
