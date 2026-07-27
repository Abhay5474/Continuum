package io.continuum.api;

import io.continuum.cascade.ResponseCascadeService;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Cascade settings and evidence.
 *
 * <p>Tenant-scoped throughout. The threshold governs one account's own quality
 * and spend, so unlike routing there is nothing engine-wide here and nothing
 * needing the operator.
 */
@RestController
@RequestMapping("/api/portal/developer/cascade")
public class CascadeController {

    private final ResponseCascadeService cascade;

    public CascadeController(ResponseCascadeService cascade) {
        this.cascade = cascade;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return cascade.status(dev(req));
    }

    @PostMapping("/{action}")
    public Map<String, Object> toggle(HttpServletRequest req, @PathVariable String action) {
        return cascade.setEnabled(dev(req), "enable".equalsIgnoreCase(action));
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest req, @RequestBody Settings body) {
        return cascade.configure(dev(req), body.threshold(), body.auditRate(), body.escalationCap());
    }

    /** The tiers the cascade would use, cheapest first — derived from the registry. */
    @GetMapping("/tiers")
    public List<ResponseCascadeService.Tier> tiers() {
        return cascade.tiers();
    }

    @GetMapping("/decisions")
    public List<Map<String, Object>> decisions(HttpServletRequest req,
                                               @RequestParam(defaultValue = "40") int limit) {
        return cascade.recent(dev(req), limit);
    }

    /** Clears decisions and the calibration curve built from them. */
    @DeleteMapping
    public Map<String, Object> reset(HttpServletRequest req) {
        return cascade.reset(dev(req));
    }

    public record Settings(Double threshold, Double auditRate, Double escalationCap) {
    }
}
