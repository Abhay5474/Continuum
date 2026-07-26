package io.continuum.api;

import io.continuum.billing.BillingService;
import io.continuum.developer.DeveloperService;
import io.continuum.persistence.entity.DeveloperApiKeyEntity;
import io.continuum.persistence.entity.DeveloperEntity;
import io.continuum.vault.CredentialVaultService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Features 2 & 3 — developer onboarding, Continuum API key issuance, and secure
 * provider-credential management. Protected by {@code X-Admin-Token}.
 *
 * Secrets are write-only: plaintext API keys are returned exactly once at
 * creation; provider secrets are never returned at all.
 */
@RestController
@RequestMapping("/api/admin")
public class DeveloperAdminController {

    private final DeveloperService developers;
    private final CredentialVaultService vault;
    private final BillingService billing;

    public DeveloperAdminController(DeveloperService developers, CredentialVaultService vault,
                                    BillingService billing) {
        this.developers = developers;
        this.vault = vault;
        this.billing = billing;
    }

    /**
     * Puts a developer on a plan without payment.
     *
     * <p>The counterpart to refusing unpaid self-serve upgrades: manual invoicing,
     * trials and enterprise deals are real, and they belong here behind the admin
     * token rather than on an endpoint every customer can call. The grant is
     * recorded against whoever made it.
     */
    @PostMapping("/developers/{id}/plan")
    public Map<String, Object> grantPlan(@PathVariable String id, @RequestBody GrantPlan req) {
        BillingService.Plan plan;
        try {
            plan = BillingService.Plan.valueOf(req.plan().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown plan: " + req.plan());
        }
        var b = billing.grantPlan(id, plan, req.grantedBy());
        return Map.of("developerId", id, "plan", b.getPlan(), "planSource", b.getPlanSource(),
                "grantedBy", b.getGrantedBy() == null ? "operator" : b.getGrantedBy(),
                "monthlyTokenQuota", b.getMonthlyTokenQuota());
    }

    public record GrantPlan(String plan, String grantedBy) {
    }

    @PostMapping("/developers")
    public DeveloperEntity create(@RequestBody CreateDeveloper req) {
        return developers.createDeveloper(req.name(), req.email());
    }

    @GetMapping("/developers")
    public List<DeveloperEntity> list() {
        return developers.all();
    }

    /** Issue a new API key. The plaintext is returned ONCE and never stored. */
    @PostMapping("/developers/{id}/keys")
    public Map<String, Object> issueKey(@PathVariable String id) {
        DeveloperService.IssuedKey issued = developers.issueKey(id);
        return Map.of("id", issued.id(), "apiKey", issued.plaintextKey(),
                "warning", "Store this key now — it will not be shown again.");
    }

    @GetMapping("/developers/{id}/keys")
    public List<Map<String, Object>> keys(@PathVariable String id) {
        return developers.keysFor(id).stream().map(this::keyMeta).toList();
    }

    @DeleteMapping("/keys/{keyId}")
    public Map<String, Object> revoke(@PathVariable Long keyId) {
        return Map.of("revoked", developers.revokeKey(keyId));
    }

    /** Store a developer's own provider key (encrypted; never echoed back). */
    @PostMapping("/developers/{id}/credentials")
    public Map<String, Object> storeCredential(@PathVariable String id, @RequestBody StoreCredential req) {
        if (developers.find(id).isEmpty()) {
            throw new IllegalArgumentException("No such developer: " + id);
        }
        vault.store(id, req.provider(), req.secret());
        return Map.of("stored", true, "provider", req.provider());
    }

    @GetMapping("/developers/{id}/credentials")
    public List<CredentialVaultService.CredentialInfo> credentials(@PathVariable String id) {
        return vault.listProviders(id); // metadata only — never the secret
    }

    @DeleteMapping("/developers/{id}/credentials/{provider}")
    public Map<String, Object> deleteCredential(@PathVariable String id, @PathVariable String provider) {
        vault.delete(id, provider);
        return Map.of("deleted", true, "provider", provider);
    }

    private Map<String, Object> keyMeta(DeveloperApiKeyEntity k) {
        return Map.of("id", k.getId(), "prefix", k.getKeyPrefix(),
                "active", k.isActive(), "createdAt", k.getCreatedAt().toString());
    }

    public record CreateDeveloper(String name, String email) {
    }

    public record StoreCredential(String provider, String secret) {
    }
}
