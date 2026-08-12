package io.continuum.api;

import io.continuum.cache.SemanticCacheService;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Semantic cache settings and telemetry.
 *
 * <p>Tenant-scoped throughout: the cache holds one account's prompts and
 * answers, so both the configuration and the entries belong to the signed-in
 * developer. Nothing here is engine-wide, so nothing here needs the operator.
 */
@RestController
@RequestMapping("/api/portal/developer/cache")
public class SemanticCacheController {

    private final SemanticCacheService cache;

    public SemanticCacheController(SemanticCacheService cache) {
        this.cache = cache;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return cache.status(dev(req));
    }

    /**
     * Turns the cache on or off.
     *
     * <p>Only "enable" and "disable" are accepted. It used to read anything that
     * was not "enable" as a disable and answer 200, so {@code POST /cache/clear}
     * — a plausible guess, and the wrong verb for clearing — silently turned the
     * cache off and told the caller it had succeeded. A path typo must not be
     * able to disable a feature.
     */
    @PostMapping("/{action}")
    public ResponseEntity<Map<String, Object>> toggle(HttpServletRequest req, @PathVariable String action) {
        boolean enable = "enable".equalsIgnoreCase(action);
        if (!enable && !"disable".equalsIgnoreCase(action)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "unknown_action",
                    "message", "Expected 'enable' or 'disable'. To empty the cache, DELETE this resource."));
        }
        return ResponseEntity.ok(cache.setEnabled(dev(req), enable));
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest req, @RequestBody Settings body) {
        return cache.configure(dev(req), body.similarityThreshold(), body.ttlSeconds());
    }

    @GetMapping("/entries")
    public List<Map<String, Object>> entries(HttpServletRequest req,
                                             @RequestParam(defaultValue = "25") int limit) {
        return cache.recent(dev(req), limit);
    }

    @DeleteMapping
    public Map<String, Object> clear(HttpServletRequest req) {
        return cache.clear(dev(req));
    }

    public record Settings(Double similarityThreshold, Integer ttlSeconds) {
    }
}
