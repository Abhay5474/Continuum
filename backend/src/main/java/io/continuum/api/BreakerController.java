package io.continuum.api;

import io.continuum.drift.SemanticBreakerService;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Semantic circuit breaker. Tenant-scoped: an account observes its own traffic
 * and trips its own breakers, so no account can reroute another's. OFF by default.
 */
@RestController
@RequestMapping("/api/portal/developer/breaker")
public class BreakerController {

    private final SemanticBreakerService breaker;

    public BreakerController(SemanticBreakerService breaker) {
        this.breaker = breaker;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return breaker.status(dev(req));
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest req, @RequestBody Settings body) {
        return breaker.configure(dev(req), body.enabled(), body.warmup(), body.slack(),
                body.threshold(), body.cooldownSeconds());
    }

    @GetMapping("/events")
    public List<Map<String, Object>> events(HttpServletRequest req,
                                            @RequestParam(defaultValue = "30") int limit) {
        return breaker.recentEvents(dev(req), limit);
    }

    /** Closes one breaker and makes it relearn its baseline. */
    @PostMapping("/reset")
    public Map<String, Object> reset(HttpServletRequest req, @RequestParam String provider,
                                     @RequestParam String model) {
        return breaker.reset(dev(req), provider, model);
    }

    @DeleteMapping
    public Map<String, Object> clear(HttpServletRequest req) {
        return breaker.clear(dev(req));
    }

    public record Settings(Boolean enabled, Integer warmup, Double slack, Double threshold,
                           Integer cooldownSeconds) {
    }
}
