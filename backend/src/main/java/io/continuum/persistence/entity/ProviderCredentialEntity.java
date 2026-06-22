package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A developer's provider API key (e.g. their own Gemini/Groq key), stored
 * AES-256-GCM encrypted. The plaintext secret is NEVER persisted and NEVER
 * returned through any API — only decrypted in-memory at call time.
 */
@Entity
@Table(name = "developer_provider_credentials",
        uniqueConstraints = @UniqueConstraint(name = "uq_dev_provider", columnNames = {"developer_id", "provider"}))
public class ProviderCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    @Column(name = "encrypted_secret", nullable = false, columnDefinition = "text")
    private String encryptedSecret;

    @Column(name = "key_version", nullable = false)
    private int keyVersion = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ProviderCredentialEntity() {
    }

    public ProviderCredentialEntity(String developerId, String provider, String encryptedSecret) {
        this.developerId = developerId;
        this.provider = provider;
        this.encryptedSecret = encryptedSecret;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getProvider() { return provider; }
    public String getEncryptedSecret() { return encryptedSecret; }
    public void setEncryptedSecret(String encryptedSecret) {
        this.encryptedSecret = encryptedSecret;
        this.updatedAt = Instant.now();
    }
    public int getKeyVersion() { return keyVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
