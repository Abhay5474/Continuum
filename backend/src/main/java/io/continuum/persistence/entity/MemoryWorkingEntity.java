package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Tier 1 — hot working context. Strict TTL; consolidated into episodic summaries. */
@Entity
@Table(name = "memory_working", indexes = {
        @Index(name = "idx_mem_working_dev", columnList = "developer_id, session_id"),
        @Index(name = "idx_mem_working_exp", columnList = "expires_at")})
public class MemoryWorkingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "role", nullable = false, length = 24)
    private String role;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "tokens", nullable = false)
    private int tokens;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected MemoryWorkingEntity() {
    }

    public MemoryWorkingEntity(String developerId, String sessionId, String role,
                               String content, int tokens, Instant expiresAt) {
        this.developerId = developerId;
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.tokens = tokens;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getSessionId() { return sessionId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public int getTokens() { return tokens; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}
