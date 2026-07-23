package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A Continuum API key (cnt_live_...) issued to a developer.
 *
 * The plaintext key is shown ONCE at creation and never stored. We persist only
 * a salted hash plus a short non-secret prefix used for fast lookup.
 */
@Entity
@Table(name = "developer_api_keys",
        indexes = @Index(name = "idx_apikey_prefix", columnList = "key_prefix"))
public class DeveloperApiKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    /** Non-secret lookup hint (e.g. "cnt_live_a1b2c3"). */
    @Column(name = "key_prefix", nullable = false, length = 32)
    private String keyPrefix;

    @Column(name = "hashed_key", nullable = false, length = 128)
    private String hashedKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "label", length = 120)
    private String label;

    protected DeveloperApiKeyEntity() {
    }

    public DeveloperApiKeyEntity(String developerId, String keyPrefix, String hashedKey) {
        this.developerId = developerId;
        this.keyPrefix = keyPrefix;
        this.hashedKey = hashedKey;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getHashedKey() { return hashedKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void revoke() { this.revokedAt = Instant.now(); }
    public boolean isActive() { return revokedAt == null; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void touch() { this.lastUsedAt = Instant.now(); }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}
