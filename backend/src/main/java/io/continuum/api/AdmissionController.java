package io.continuum.api;

import io.continuum.admission.AdmissionService;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Congestion-controlled admission. Tenant-scoped: with bring-your-own-key each
 * account has its own quota at the provider, so each account infers its own
 * limit. OFF by default.
 */
@RestController
@RequestMapping("/api/portal/developer/admission")
public class AdmissionController {

    private final AdmissionService admission;

    public AdmissionController(AdmissionService admission) {
        this.admission = admission;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return admission.status(dev(req));
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest req, @RequestBody Settings body) {
        return admission.configure(dev(req), body.enabled());
    }

    /** Forgets the learned limits, so a changed provider quota can be re-inferred. */
    @DeleteMapping
    public Map<String, Object> reset(HttpServletRequest req) {
        admission.reset(dev(req));
        return admission.status(dev(req));
    }

    public record Settings(Boolean enabled) {
    }
}
