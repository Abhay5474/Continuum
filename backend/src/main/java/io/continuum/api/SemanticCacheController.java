package io.continuum.api;

import io.continuum.cache.SemanticCacheService;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
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

    @PostMapping("/{action}")
    public Map<String, Object> toggle(HttpServletRequest req, @PathVariable String action) {
        return cache.setEnabled(dev(req), "enable".equalsIgnoreCase(action));
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
