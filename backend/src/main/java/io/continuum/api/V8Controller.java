package io.continuum.api;

import io.continuum.compression.PromptCompressionService;
import io.continuum.firewall.PromptFirewallService;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * V8 toggles — session-scoped via the existing {@link PortalAuthFilter}.
 * Both features are OFF by default; while off the prompt is sent verbatim and
 * unscanned, exactly as before.
 */
@RestController
@RequestMapping("/api/portal/developer/v8")
public class V8Controller {

    private final PromptCompressionService compression;
    private final PromptFirewallService firewall;

    public V8Controller(PromptCompressionService compression, PromptFirewallService firewall) {
        this.compression = compression;
        this.firewall = firewall;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        String d = dev(req);
        return Map.of("compressionEnabled", compression.enabledFor(d),
                "firewallEnabled", firewall.enabledFor(d));
    }

    @PostMapping("/compression/{action}")
    public Map<String, Object> compression(HttpServletRequest req, @PathVariable String action) {
        boolean enable = "enable".equalsIgnoreCase(action);
        compression.setEnabled(dev(req), enable);
        return status(req);
    }

    @PostMapping("/firewall/{action}")
    public Map<String, Object> firewall(HttpServletRequest req, @PathVariable String action) {
        boolean enable = "enable".equalsIgnoreCase(action);
        firewall.setEnabled(dev(req), enable);
        return status(req);
    }

    @GetMapping("/compression/profile")
    public Map<String, Object> compressionProfile(HttpServletRequest req) {
        return compression.profile(dev(req));
    }

    @GetMapping("/firewall/profile")
    public Map<String, Object> firewallProfile(HttpServletRequest req) {
        return firewall.profile(dev(req));
    }
}
