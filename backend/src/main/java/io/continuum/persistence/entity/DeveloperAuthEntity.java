package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Portal sign-in credentials and per-developer routing preference. Kept in its
 * own table so the existing {@code developers} table is not altered.
 */
@Entity
@Table(name = "developer_auth")
public class DeveloperAuthEntity {

    @Id
    @Column(name = "developer_id", length = 64)
    private String developerId;

    @Column(name = "password_hash", nullable = false, length = 256)
    private String passwordHash;

    /**
     * When true (default), the gateway prefers this developer's own provider keys
     * and falls back to the platform/mock only if they fail. When false, requests
     * run on the platform's global keys.
     */
    @Column(name = "use_own_keys_primary", nullable = false)
    private boolean useOwnKeysPrimary = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DeveloperAuthEntity() {
    }

    public DeveloperAuthEntity(String developerId, String passwordHash) {
        this.developerId = developerId;
        this.passwordHash = passwordHash;
        this.useOwnKeysPrimary = true;
        this.createdAt = Instant.now();
    }

    public String getDeveloperId() { return developerId; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isUseOwnKeysPrimary() { return useOwnKeysPrimary; }
    public void setUseOwnKeysPrimary(boolean v) { this.useOwnKeysPrimary = v; }
    public Instant getCreatedAt() { return createdAt; }
}
