package io.continuum.api;

import io.continuum.developer.DeveloperService;
import io.continuum.gateway.GatewayDtos;
import io.continuum.gateway.GatewayService;
import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import io.continuum.portal.CredentialVerifier;
import io.continuum.portal.PortalAuthFilter;
import io.continuum.portal.PortalService;
import io.continuum.vault.CredentialVaultService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-service developer portal, scoped strictly to the authenticated developer
 * (resolved from the session by {@link PortalAuthFilter}). A developer can manage
 * their own API keys and provider credentials, toggle their routing preference,
 * test prompts in a sandbox, and view their own analytics — never anyone else's.
 */
@RestController
@RequestMapping("/api/portal/developer")
public class PortalDeveloperController {

    private final DeveloperService developers;
    private final CredentialVaultService vault;
    private final CredentialVerifier verifier;
    private final PortalService portal;
    private final GatewayService gateway;
    private final GatewayRequestLogRepository logs;

    public PortalDeveloperController(DeveloperService developers, CredentialVaultService vault,
                                     CredentialVerifier verifier, PortalService portal,
                                     GatewayService gateway, GatewayRequestLogRepository logs) {
        this.developers = developers;
        this.vault = vault;
        this.verifier = verifier;
        this.portal = portal;
        this.gateway = gateway;
        this.logs = logs;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest req) {
        String id = dev(req);
        Object a = req.getAttribute(io.continuum.portal.PortalAuthFilter.ACTOR_ID_ATTRIBUTE);
        String actor = a == null ? id : (String) a;
        // id/name/email describe the account, as they always have. "you" is the
        // person signed in, which differs for a team member — whose personal
        // settings (email, password) are their own, not the owner's.
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        developers.find(id).ifPresentOrElse(d -> {
            out.put("id", d.getId());
            out.put("name", d.getName());
            out.put("email", d.getEmail());
            out.put("useOwnKeysPrimary", portal.useOwnKeysPrimary(id));
        }, () -> out.put("id", id));
        out.put("member", !actor.equals(id));
        developers.find(actor).ifPresent(d ->
                out.put("you", Map.of("id", d.getId(), "name", d.getName(), "email", d.getEmail())));
        return out;
    }

    // --- API keys (self-service) ---

    @GetMapping("/keys")
    public List<Map<String, Object>> keys(HttpServletRequest req) {
        Map<Long, String> appNames = new java.util.HashMap<>();
        developers.appsFor(dev(req)).forEach(a -> appNames.put(a.getId(), a.getName()));
        return developers.keysFor(dev(req)).stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.getId());
            m.put("prefix", k.getKeyPrefix());
            m.put("label", k.getLabel());
            m.put("appId", k.getAppId());
            m.put("appName", k.getAppId() == null ? null : appNames.get(k.getAppId()));
            m.put("active", k.isActive());
            m.put("createdAt", k.getCreatedAt().toString());
            m.put("lastUsedAt", k.getLastUsedAt() == null ? null : k.getLastUsedAt().toString());
            return m;
        }).toList();
    }

    @PostMapping("/keys")
    public Map<String, Object> issueKey(HttpServletRequest req, @RequestBody(required = false) IssueKey body) {
        String label = body == null ? null : body.label();
        Long appId = body == null ? null : body.appId();
        DeveloperService.IssuedKey issued = developers.issueKey(dev(req), label, appId);
        return Map.of("id", issued.id(), "apiKey", issued.plaintextKey(),
                "warning", "Store this key now — it will not be shown again.");
    }

    /**
     * @param label the app's name when a new app is started
     * @param appId an existing app the key joins — to replace a key and keep
     *              the app's history; absent starts a new app
     */
    public record IssueKey(String label, Long appId) {
    }

    /** Renames a key, which is renaming the app it speaks for. */
    @PatchMapping("/keys/{keyId}")
    public Map<String, Object> renameKey(HttpServletRequest req, @PathVariable Long keyId,
                                         @RequestBody(required = false) Rename body) {
        var key = developers.keysFor(dev(req)).stream().filter(k -> k.getId().equals(keyId)).findFirst()
                .orElseThrow(() -> new io.continuum.portal.RequestScope.NotFoundException("No such key."));
        if (key.getAppId() == null) {
            throw new io.continuum.portal.RequestScope.NotFoundException("This key belongs to no app.");
        }
        return describe(developers.renameApp(dev(req), key.getAppId(), body == null ? null : body.name()),
                developers.keysFor(dev(req)));
    }

    /**
     * The account's apps: one per application calling Continuum. The console's
     * app selector is built from this.
     */
    @GetMapping("/apps")
    public List<Map<String, Object>> apps(HttpServletRequest req) {
        var keys = developers.keysFor(dev(req));
        return developers.appsFor(dev(req)).stream().map(a -> describe(a, keys)).toList();
    }

    @PatchMapping("/apps/{appId}")
    public Map<String, Object> renameApp(HttpServletRequest req, @PathVariable Long appId,
                                         @RequestBody(required = false) Rename body) {
        return describe(developers.renameApp(dev(req), appId, body == null ? null : body.name()),
                developers.keysFor(dev(req)));
    }

    public record Rename(String name) {
    }

    private static Map<String, Object> describe(io.continuum.persistence.entity.AppEntity a,
                                                List<io.continuum.persistence.entity.DeveloperApiKeyEntity> keys) {
        var mine = keys.stream().filter(k -> a.getId().equals(k.getAppId())).toList();
        java.time.Instant lastUsed = mine.stream().map(k -> k.getLastUsedAt())
                .filter(java.util.Objects::nonNull).max(java.util.Comparator.naturalOrder()).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("name", a.getName());
        m.put("keys", mine.size());
        m.put("activeKeys", mine.stream().filter(k -> k.isActive()).count());
        m.put("createdAt", a.getCreatedAt().toString());
        m.put("lastUsedAt", lastUsed == null ? null : lastUsed.toString());
        return m;
    }

    @DeleteMapping("/keys/{keyId}")
    public Map<String, Object> revokeKey(HttpServletRequest req, @PathVariable Long keyId) {
        // Ensure the key belongs to this developer before revoking.
        boolean owned = developers.keysFor(dev(req)).stream().anyMatch(k -> k.getId().equals(keyId));
        if (!owned) {
            throw new io.continuum.portal.RequestScope.NotFoundException("No such key.");
        }
        return Map.of("revoked", developers.revokeKey(keyId));
    }

    // --- provider credential vault (write-only secrets) ---

    @GetMapping("/credentials")
    public List<CredentialVaultService.CredentialInfo> credentials(HttpServletRequest req) {
        return vault.listProviders(dev(req)); // metadata only — never the secret
    }

    @PostMapping("/credentials")
    public Map<String, Object> storeCredential(HttpServletRequest req, @RequestBody StoreCredential body) {
        vault.store(dev(req), body.provider(), body.secret());
        return Map.of("stored", true, "provider", body.provider());
    }

    @DeleteMapping("/credentials/{provider}")
    public Map<String, Object> deleteCredential(HttpServletRequest req, @PathVariable String provider) {
        // Idempotent (a repeat is not an error), but it says whether anything
        // was there: it used to report deleted:true for a key that never existed.
        return Map.of("deleted", vault.delete(dev(req), provider), "provider", provider);
    }

    @PostMapping("/credentials/{provider}/verify")
    public CredentialVerifier.Result verifyCredential(HttpServletRequest req, @PathVariable String provider) {
        return verifier.verify(dev(req), provider);
    }

    // --- routing preference toggle ---

    @PutMapping("/routing-preference")
    public Map<String, Object> setPreference(HttpServletRequest req, @RequestBody RoutingPreference body) {
        portal.setUseOwnKeysPrimary(dev(req), body.useOwnKeysPrimary());
        return Map.of("useOwnKeysPrimary", body.useOwnKeysPrimary());
    }

    // --- sandbox playground (session-auth, no cnt_live_ key needed in the browser) ---

    @PostMapping("/playground")
    public GatewayDtos.ChatResponse playground(HttpServletRequest req, @RequestBody GatewayDtos.ChatRequest body) {
        return gateway.chat(dev(req), body);
    }

    // --- developer-scoped analytics ---

    @GetMapping("/stats")
    public Map<String, Object> stats(HttpServletRequest req) {
        String id = dev(req);
        long success = logs.countByDeveloperIdAndSuccess(id, true);
        long failed = logs.countByDeveloperIdAndSuccess(id, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalRequests", success + failed);
        out.put("successful", success);
        out.put("failed", failed);
        out.put("successRate", (success + failed) == 0 ? 1.0 : (double) success / (success + failed));
        out.put("failuresPrevented", logs.totalFailoversForDeveloper(id));
        out.put("totalTokens", logs.totalTokensForDeveloper(id));
        out.put("totalCostUsd", logs.totalCostForDeveloper(id));
        return out;
    }

    @GetMapping("/requests")
    public List<GatewayRequestLogEntity> requests(HttpServletRequest req,
                                                  @RequestParam(defaultValue = "25") int limit) {
        // Bounded both ways: zero or a negative size used to fail the page
        // request, and an enormous one read the whole log in one response.
        int size = Math.max(1, Math.min(500, limit));
        return logs.findByDeveloperIdOrderByCreatedAtDesc(dev(req), PageRequest.of(0, size)).getContent();
    }

    public record StoreCredential(String provider, String secret) {
    }

    public record RoutingPreference(boolean useOwnKeysPrimary) {
    }
}
