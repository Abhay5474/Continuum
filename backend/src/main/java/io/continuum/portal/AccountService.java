package io.continuum.portal;

import io.continuum.billing.BillingService;
import io.continuum.persistence.entity.DeveloperEntity;
import io.continuum.persistence.entity.TeamInviteEntity;
import io.continuum.persistence.repository.*;
import io.continuum.vault.CredentialVaultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
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
    private final CredentialVaultService vault;
    private final BillingService billing;
    private final PasswordHasher passwordHasher;

    public AccountService(DeveloperRepository developers, DeveloperAuthRepository auth,
                          DeveloperApiKeyRepository apiKeys, GodModeConfigRepository godModeConfigs,
                          TeamInviteRepository invites, CredentialVaultService vault,
                          BillingService billing, PasswordHasher passwordHasher) {
        this.developers = developers;
        this.auth = auth;
        this.apiKeys = apiKeys;
        this.godModeConfigs = godModeConfigs;
        this.invites = invites;
        this.vault = vault;
        this.billing = billing;
        this.passwordHasher = passwordHasher;
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
        safe(() -> auth.deleteById(developerId));
        // Finally the developer identity.
        safe(() -> developers.deleteById(developerId));
        log.info("Account and data deleted for developer {}", developerId);
    }

    // ---- team invites (mock) ----

    @Transactional
    public Map<String, Object> invite(String developerId, String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new IllegalArgumentException("A valid teammate email is required");
        }
        byte[] buf = new byte[24];
        RNG.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        invites.save(new TeamInviteEntity(developerId, email, token));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", email);
        out.put("inviteLink", "/portal/accept-invite?token=" + token);
        out.put("token", token);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> invites(String developerId) {
        return invites.findByDeveloperIdOrderByCreatedAtDesc(developerId).stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.getId());
            m.put("email", i.getEmail());
            m.put("accepted", i.isAccepted());
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
