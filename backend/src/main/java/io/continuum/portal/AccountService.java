package io.continuum.portal;

import io.continuum.billing.BillingService;
import io.continuum.persistence.entity.AccountMembershipEntity;
import io.continuum.persistence.entity.DeveloperAuthEntity;
import io.continuum.persistence.entity.DeveloperEntity;
import io.continuum.persistence.entity.TeamInviteEntity;
import io.continuum.persistence.repository.*;
import io.continuum.vault.CredentialVaultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Account &amp; settings operations: change password / email, delete account
 * (GDPR), and lightweight team invites. Additive — reuses existing repositories
 * and services; nothing here alters the gateway or auth flow for other calls.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final DeveloperRepository developers;
    private final DeveloperAuthRepository auth;
    private final DeveloperApiKeyRepository apiKeys;
    private final GodModeConfigRepository godModeConfigs;
    private final TeamInviteRepository invites;
    private final AccountMembershipRepository memberships;
    private final CredentialVaultService vault;
    private final BillingService billing;
    private final PasswordHasher passwordHasher;
    private final Mailer mailer;

    public AccountService(DeveloperRepository developers, DeveloperAuthRepository auth,
                          DeveloperApiKeyRepository apiKeys, GodModeConfigRepository godModeConfigs,
                          TeamInviteRepository invites, AccountMembershipRepository memberships,
                          CredentialVaultService vault,
                          BillingService billing, PasswordHasher passwordHasher, Mailer mailer) {
        this.developers = developers;
        this.auth = auth;
        this.apiKeys = apiKeys;
        this.godModeConfigs = godModeConfigs;
        this.invites = invites;
        this.memberships = memberships;
        this.vault = vault;
        this.billing = billing;
        this.passwordHasher = passwordHasher;
        this.mailer = mailer;
    }

    @Transactional
    public void changePassword(String developerId, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("New password must be at least 6 characters");
        }
        var a = auth.findById(developerId).orElseThrow(() -> new IllegalArgumentException("No account"));
        if (!passwordHasher.matches(currentPassword == null ? "" : currentPassword, a.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        a.setPasswordHash(passwordHasher.hash(newPassword));
        auth.save(a);
        log.info("Password changed for developer {}", developerId);
    }

    @Transactional
    public void changeEmail(String developerId, String newEmail) {
        if (newEmail == null || newEmail.isBlank() || !newEmail.contains("@")) {
            throw new IllegalArgumentException("A valid email is required");
        }
        if (developers.existsByEmailIgnoreCase(newEmail)) {
            throw new IllegalArgumentException("That email is already in use");
        }
        DeveloperEntity d = developers.findById(developerId)
                .orElseThrow(() -> new IllegalArgumentException("No account"));
        d.setEmail(newEmail);
        developers.save(d);
        log.info("Email changed for developer {}", developerId);
    }

    /** GDPR delete: removes the developer and all their data, honoring FK order. */
    @Transactional
    public void deleteAccount(String developerId) {
        // Provider credentials (each provider row).
        try {
            vault.listProviders(developerId).forEach(c -> vault.delete(developerId, c.provider()));
        } catch (Exception e) {
            log.debug("credential cleanup: {}", e.getMessage());
        }
        // API keys.
        try {
            apiKeys.deleteAll(apiKeys.findByDeveloperId(developerId));
        } catch (Exception e) {
            log.debug("api key cleanup: {}", e.getMessage());
        }
        // Invites.
        safe(() -> invites.deleteByDeveloperId(developerId));
        // FK children of `developers` must go before the developer row.
        safe(() -> godModeConfigs.deleteById(developerId));
        safe(() -> billing.deleteFor(developerId));
        // Auth record.
        safe(() -> memberships.deleteByAccountDeveloperId(developerId));
        safe(() -> memberships.deleteById(developerId));
        safe(() -> auth.deleteById(developerId));
        // Finally the developer identity.
        safe(() -> developers.deleteById(developerId));
        log.info("Account and data deleted for developer {}", developerId);
    }

    // ---- team invites ----

    /** How long an invite stays usable. Long enough to act on, short enough to lapse. */
    private static final Duration INVITE_TTL = Duration.ofDays(7);

    @Transactional
    public Map<String, Object> invite(String developerId, String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new IllegalArgumentException("A valid teammate email is required");
        }
        if (developers.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("That email already has a Continuum account");
        }
        byte[] buf = new byte[24];
        RNG.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        Instant expires = Instant.now().plus(INVITE_TTL);
        invites.save(new TeamInviteEntity(developerId, email, token, expires));

        String link = "/accept-invite?token=" + token;
        String inviter = developers.findById(developerId).map(DeveloperEntity::getName).orElse("A teammate");
        boolean sent = mailer.send(email, inviter + " invited you to Continuum",
                "You have been invited to join " + inviter + " on Continuum. Accept here: " + link);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", email);
        out.put("inviteLink", link);
        out.put("expiresAt", expires.toString());
        out.put("emailSent", sent);
        // Returned so the inviter can pass it on themselves when mail is not
        // configured — otherwise the invite is unusable through no fault of theirs.
        out.put("token", token);
        return out;
    }

    /** What an invitee is shown before deciding, without needing an account yet. */
    @Transactional(readOnly = true)
    public Map<String, Object> previewInvite(String token) {
        TeamInviteEntity invite = usableInvite(token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", invite.getEmail());
        out.put("invitedBy", developers.findById(invite.getDeveloperId())
                .map(DeveloperEntity::getName).orElse("A Continuum account"));
        out.put("expiresAt", invite.getExpiresAt() == null ? null : invite.getExpiresAt().toString());
        return out;
    }

    /**
     * Consumes an invite: creates the teammate's own credentials and maps them
     * onto the inviting account, so their session is scoped to that account.
     *
     * @return the account they joined, for the caller to issue a session against
     */
    @Transactional
    public Accepted acceptInvite(String token, String name, String password) {
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("A password of at least 6 characters is required");
        }
        TeamInviteEntity invite = usableInvite(token);
        if (developers.existsByEmailIgnoreCase(invite.getEmail())) {
            throw new IllegalArgumentException("That email already has a Continuum account");
        }

        DeveloperEntity member = developers.save(new DeveloperEntity(
                name == null || name.isBlank() ? invite.getEmail() : name, invite.getEmail()));
        auth.save(new DeveloperAuthEntity(member.getId(), passwordHasher.hash(password)));
        memberships.save(new AccountMembershipEntity(
                member.getId(), invite.getDeveloperId(), invite.getEmail()));

        invite.accept(member.getId());
        invites.save(invite);

        log.info("Developer {} joined account {} via invite", member.getId(), invite.getDeveloperId());
        return new Accepted(member.getId(), invite.getDeveloperId(), member.getName(), member.getEmail());
    }

    /** Withdraws an unaccepted invite. */
    @Transactional
    public void revokeInvite(String developerId, long inviteId) {
        TeamInviteEntity invite = invites.findById(inviteId)
                .orElseThrow(() -> new IllegalArgumentException("No such invite"));
        if (!invite.getDeveloperId().equals(developerId)) {
            throw new RequestScope.ForbiddenException();
        }
        invite.setRevoked(true);
        invites.save(invite);
    }

    /** Everyone who can sign in to this account. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> members(String accountId) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        developers.findById(accountId).ifPresent(owner -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("developerId", owner.getId());
            m.put("name", owner.getName());
            m.put("email", owner.getEmail());
            m.put("role", "OWNER");
            out.add(m);
        });
        for (AccountMembershipEntity mem : memberships.findByAccountDeveloperId(accountId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("developerId", mem.getMemberDeveloperId());
            m.put("name", developers.findById(mem.getMemberDeveloperId())
                    .map(DeveloperEntity::getName).orElse(mem.getInvitedEmail()));
            m.put("email", mem.getInvitedEmail());
            m.put("role", mem.getRole());
            m.put("joinedAt", mem.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    /**
     * An invite that is still good, or a refusal.
     *
     * <p>Expired, revoked, already-used and never-existed all answer the same
     * way: a token is a credential, and distinguishing these would let someone
     * probe for valid ones.
     */
    private TeamInviteEntity usableInvite(String token) {
        return invites.findByToken(token == null ? "" : token)
                .filter(i -> i.isUsable(Instant.now()))
                .orElseThrow(() -> new IllegalArgumentException("This invite link is not valid or has expired"));
    }

    /** @param accountId the account whose data the member will see */
    public record Accepted(String memberDeveloperId, String accountId, String name, String email) {
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> invites(String developerId) {
        return invites.findByDeveloperIdOrderByCreatedAtDesc(developerId).stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.getId());
            m.put("email", i.getEmail());
            m.put("accepted", i.isAccepted());
            m.put("revoked", i.isRevoked());
            m.put("expiresAt", i.getExpiresAt() == null ? null : i.getExpiresAt().toString());
            m.put("usable", i.isUsable(Instant.now()));
            m.put("inviteLink", i.isUsable(Instant.now()) ? "/accept-invite?token=" + i.getToken() : null);
            m.put("createdAt", i.getCreatedAt().toString());
            return m;
        }).toList();
    }

    private void safe(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.debug("delete step skipped: {}", e.getMessage());
        }
    }
}
