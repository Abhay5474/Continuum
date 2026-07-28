package io.continuum.api;

import io.continuum.degradation.DegradationService;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Graceful degradation. Tenant-scoped; OFF by default. */
@RestController
@RequestMapping("/api/portal/developer/degradation")
public class DegradationController {

    private final DegradationService degradation;

    public DegradationController(DegradationService degradation) {
        this.degradation = degradation;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return degradation.status(dev(req));
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest req, @RequestBody Settings body) {
        return degradation.configure(dev(req), body.enabled());
    }

    @DeleteMapping
    public Map<String, Object> clear(HttpServletRequest req) {
        return degradation.clear(dev(req));
    }

    public record Settings(Boolean enabled) {
    }
}
