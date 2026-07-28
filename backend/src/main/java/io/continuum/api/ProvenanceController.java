package io.continuum.api;

import io.continuum.portal.PortalAuthFilter;
import io.continuum.provenance.ProvenanceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Decision provenance. Tenant-scoped; OFF by default. */
@RestController
@RequestMapping("/api/portal/developer/provenance")
public class ProvenanceController {

    private final ProvenanceService provenance;

    public ProvenanceController(ProvenanceService provenance) {
        this.provenance = provenance;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return provenance.status(dev(req));
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest req, @RequestBody Settings body) {
        return provenance.configure(dev(req), body.enabled());
    }

    @GetMapping("/requests")
    public List<String> requests(HttpServletRequest req,
                                 @RequestParam(defaultValue = "30") int limit) {
        return provenance.requests(dev(req), limit);
    }

    @GetMapping("/requests/{requestId}")
    public Map<String, Object> graph(HttpServletRequest req, @PathVariable String requestId) {
        return provenance.graph(dev(req), requestId);
    }

    /**
     * The same request as OpenTelemetry spans.
     *
     * <p>So this can land in the tracing tool a developer already runs, rather
     * than only in a Continuum page.
     */
    @GetMapping("/requests/{requestId}/otel")
    public Map<String, Object> spans(HttpServletRequest req, @PathVariable String requestId) {
        return provenance.spans(dev(req), requestId);
    }

    @DeleteMapping
    public Map<String, Object> clear(HttpServletRequest req) {
        return provenance.clear(dev(req));
    }

    public record Settings(Boolean enabled) {
    }
}
