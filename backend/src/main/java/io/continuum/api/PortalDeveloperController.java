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
        return developers.find(id).map(d -> Map.<String, Object>of(
                "id", d.getId(), "name", d.getName(), "email", d.getEmail(),
                "useOwnKeysPrimary", portal.useOwnKeysPrimary(id))).orElse(Map.of("id", id));
    }

    // --- API keys (self-service) ---

    @GetMapping("/keys")
    public List<Map<String, Object>> keys(HttpServletRequest req) {
        return developers.keysFor(dev(req)).stream().map(k -> Map.<String, Object>of(
                "id", k.getId(), "prefix", k.getKeyPrefix(), "active", k.isActive(),
                "createdAt", k.getCreatedAt().toString())).toList();
    }

    @PostMapping("/keys")
    public Map<String, Object> issueKey(HttpServletRequest req) {
        DeveloperService.IssuedKey issued = developers.issueKey(dev(req));
        return Map.of("id", issued.id(), "apiKey", issued.plaintextKey(),
                "warning", "Store this key now — it will not be shown again.");
    }

    @DeleteMapping("/keys/{keyId}")
    public Map<String, Object> revokeKey(HttpServletRequest req, @PathVariable Long keyId) {
        // Ensure the key belongs to this developer before revoking.
        boolean owned = developers.keysFor(dev(req)).stream().anyMatch(k -> k.getId().equals(keyId));
        if (!owned) {
            return Map.of("revoked", false, "error", "not_found");
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
        vault.delete(dev(req), provider);
        return Map.of("deleted", true, "provider", provider);
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
        return logs.findByDeveloperIdOrderByCreatedAtDesc(dev(req), PageRequest.of(0, limit)).getContent();
    }

    public record StoreCredential(String provider, String secret) {
    }

    public record RoutingPreference(boolean useOwnKeysPrimary) {
    }
}
