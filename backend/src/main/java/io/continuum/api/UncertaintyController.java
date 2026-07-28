package io.continuum.api;

import io.continuum.portal.PortalAuthFilter;
import io.continuum.uncertainty.SemanticUncertaintyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Uncertainty settings and measurements. Tenant-scoped: the sample count and
 * temperature govern one account's own bill.
 */
@RestController
@RequestMapping("/api/portal/developer/uncertainty")
public class UncertaintyController {

    private final SemanticUncertaintyService uncertainty;

    public UncertaintyController(SemanticUncertaintyService uncertainty) {
        this.uncertainty = uncertainty;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return uncertainty.status(dev(req));
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest req, @RequestBody Settings body) {
        return uncertainty.configure(dev(req), body.mode(), body.samples(),
                body.adaptiveEnabled(), body.overturnThreshold(),
                body.temperature(), body.lowConfidence());
    }

    @GetMapping("/measurements")
    public List<Map<String, Object>> measurements(HttpServletRequest req,
                                                  @RequestParam(defaultValue = "20") int limit) {
        return uncertainty.recent(dev(req), limit);
    }

    @DeleteMapping
    public Map<String, Object> clear(HttpServletRequest req) {
        return uncertainty.clear(dev(req));
    }

    public record Settings(String mode, Integer samples, Double temperature, Double lowConfidence, Boolean adaptiveEnabled, Double overturnThreshold) {
    }
}
