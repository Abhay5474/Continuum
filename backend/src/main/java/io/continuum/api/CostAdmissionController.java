package io.continuum.api;

import io.continuum.admission.CostAdmissionService;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Cost-aware admission. Tenant-scoped; OFF by default. */
@RestController
@RequestMapping("/api/portal/developer/cost-admission")
public class CostAdmissionController {

    private final CostAdmissionService costs;

    public CostAdmissionController(CostAdmissionService costs) {
        this.costs = costs;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return costs.status(dev(req));
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest req, @RequestBody Settings body) {
        return costs.configure(dev(req), body.enabled(), body.requestsPerMin(), body.tokensPerMin());
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(HttpServletRequest req) {
        return costs.reset(dev(req));
    }

    public record Settings(Boolean enabled, Integer requestsPerMin, Integer tokensPerMin) {
    }
}
