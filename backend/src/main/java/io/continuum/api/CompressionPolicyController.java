package io.continuum.api;

import io.continuum.compression.PromptCompressionService;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Adaptive compression policy. Tenant-scoped; OFF by default. */
@RestController
@RequestMapping("/api/portal/developer/compression-policy")
public class CompressionPolicyController {

    private final PromptCompressionService compression;

    public CompressionPolicyController(PromptCompressionService compression) {
        this.compression = compression;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return compression.policyStatus(dev(req));
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest req, @RequestBody Settings body) {
        return compression.configurePolicy(dev(req), body.enabled());
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(HttpServletRequest req) {
        return compression.resetPolicyTallies(dev(req));
    }

    public record Settings(Boolean enabled) {
    }
}
