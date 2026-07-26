package io.continuum.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Maps a person's login onto the account whose data they work in.
 *
 * <p>Tenancy throughout the system keys off a single developer id. Rather than
 * thread a second concept through all of it, a teammate keeps their own
 * credentials and this row says which account their session is issued for — so
 * every existing scope check keeps working unchanged, and the id it checks is
 * now the account rather than the individual.
 */
@Entity
@Table(name = "account_memberships",
        indexes = @Index(name = "idx_membership_account", columnList = "account_developer_id"))
public class AccountMembershipEntity {

    /** The invitee's own developer id — the identity they authenticate as. */
    @Id
    @Column(name = "member_developer_id", length = 64)
    private String memberDeveloperId;

    /** The account they were invited into, and whose data they see. */
    @Column(name = "account_developer_id", nullable = false, length = 64)
    private String accountDeveloperId;

    @Column(name = "role", nullable = false, length = 16)
    private String role = "MEMBER";

    @Column(name = "invited_email", length = 200)
    private String invitedEmail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AccountMembershipEntity() {
    }

    public AccountMembershipEntity(String memberDeveloperId, String accountDeveloperId, String invitedEmail) {
        this.memberDeveloperId = memberDeveloperId;
        this.accountDeveloperId = accountDeveloperId;
        this.invitedEmail = invitedEmail;
    }

    public String getMemberDeveloperId() { return memberDeveloperId; }
    public String getAccountDeveloperId() { return accountDeveloperId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getInvitedEmail() { return invitedEmail; }
    public Instant getCreatedAt() { return createdAt; }
}
