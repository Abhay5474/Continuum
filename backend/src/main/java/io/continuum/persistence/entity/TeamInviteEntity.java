package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** A pending teammate invite (mock — generates an invite token/link). */
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

    protected TeamInviteEntity() {
    }

    public TeamInviteEntity(String developerId, String email, String token) {
        this.developerId = developerId;
        this.email = email;
        this.token = token;
    }

    public Long getId() { return id; }
    public String getDeveloperId() { return developerId; }
    public String getEmail() { return email; }
    public String getToken() { return token; }
    public boolean isAccepted() { return accepted; }
    public Instant getCreatedAt() { return createdAt; }
}
