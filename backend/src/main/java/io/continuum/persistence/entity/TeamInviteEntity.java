package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A pending teammate invite.
 *
 * <p>Expiry and revocation are not decoration: the token grants access to an
 * account, so one that never expires is a permanent key and one that cannot be
 * withdrawn is worse.
 */
@Entity
@Table(name = "team_invites",
        indexes = @Index(name = "idx_team_invites_dev", columnList = "developer_id, created_at"))
public class TeamInviteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "developer_id", nullable = false, length = 64)
    private String developerId;

    @Column(name = "email", nullable = false, length = 200)
    private String email;

    @Column(name = "token", nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "accepted", nullable = false)
    private boolean accepted = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked = false;

    /** The developer id created when this invite was accepted. */
    @Column(name = "accepted_by", length = 64)
    private String acceptedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    protected TeamInviteEntity() {
    }

    public TeamInviteEntity(String developerId, String email, String token, Instant expiresAt) {
        this.developerId = developerId;
        this.email = email;
        this.token = token;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getEmail() { return email; }
    public String getToken() { return token; }
    public boolean isAccepted() { return accepted; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
    public String getAcceptedBy() { return acceptedBy; }
    public Instant getAcceptedAt() { return acceptedAt; }

    /** Marks the invite consumed. An accepted invite is never reusable. */
    public void accept(String memberDeveloperId) {
        this.accepted = true;
        this.acceptedBy = memberDeveloperId;
        this.acceptedAt = Instant.now();
    }

    /** Usable only while unaccepted, unrevoked and unexpired. */
    public boolean isUsable(Instant now) {
        return !accepted && !revoked && (expiresAt == null || now.isBefore(expiresAt));
    }
}
