package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A developer's credential for one third-party model provider.
 *
 * <p>Holds everything <em>about</em> the connection and a reference to the
 * secret; the secret itself stays in the encrypted vault. That separation
 * matters because this row is read on every specialist call and returned to the
 * console, while the secret is decrypted only at the moment of use.
 */
@Entity
@Table(name = "specialist_connection",
        uniqueConstraints = @UniqueConstraint(name = "uq_specialist_connection",
                columnNames = {"developer_id", "name"}),
        indexes = @Index(name = "idx_specialist_connection_dev", columnList = "developer_id"))
public class SpecialistConnectionEntity {

    /** How the credential is presented to the provider. */
    public enum AuthStyle {
        /** A named header, e.g. {@code x-api-key: ...}. */
        HEADER,
        /** A query parameter, which is what Roboflow's hosted API expects. */
        QUERY,
        /** {@code Authorization: Bearer ...}. */
        BEARER,
        /**
         * {@code Authorization: Token ...} — Deepgram's scheme.
         *
         * <p>Its own style rather than a HEADER connection whose value happens
         * to start with "Token ", because that would mean storing the word
         * "Token" as part of the secret. A rotated key would then silently lose
         * its prefix and every call would 401.
         */
        TOKEN,
        /** A public endpoint with no credential. */
        NONE
    }

    public enum Status {
        /** Never proved to work. Cannot be used until it has been. */
        UNVERIFIED,
        VERIFIED,
        /** A previously good connection that has started failing. */
        FAILING
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_style", nullable = false, length = 16)
    private AuthStyle authStyle = AuthStyle.BEARER;

    @Column(name = "auth_param", length = 80)
    private String authParam;

    @Column(name = "credential_ref", length = 120)
    private String credentialRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.UNVERIFIED;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SpecialistConnectionEntity() {
    }

    public SpecialistConnectionEntity(String developerId, String name, String provider,
                                      String baseUrl, AuthStyle authStyle, String authParam) {
        this.developerId = developerId;
        this.name = name;
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.authStyle = authStyle == null ? AuthStyle.BEARER : authStyle;
        this.authParam = authParam;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getName() { return name; }
    public String getProvider() { return provider; }
    public String getBaseUrl() { return baseUrl; }
    public AuthStyle getAuthStyle() { return authStyle; }
    public String getAuthParam() { return authParam; }
    public String getCredentialRef() { return credentialRef; }
    public Status getStatus() { return status; }
    public String getLastError() { return lastError; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setCredentialRef(String ref) {
        this.credentialRef = ref;
        this.updatedAt = Instant.now();
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        this.updatedAt = Instant.now();
    }

    public void markVerified() {
        this.status = Status.VERIFIED;
        this.lastError = null;
        this.verifiedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * A connection that has worked before is marked FAILING rather than
     * UNVERIFIED: the distinction between "never worked" and "stopped working"
     * is the difference between a setup mistake and an incident.
     */
    public void markFailed(String error) {
        this.status = this.verifiedAt == null ? Status.UNVERIFIED : Status.FAILING;
        this.lastError = error == null ? null : (error.length() > 800 ? error.substring(0, 800) : error);
        this.updatedAt = Instant.now();
    }
}
