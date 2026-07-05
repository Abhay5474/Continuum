package io.continuum.api;

import io.continuum.mmu.ContextMMU;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * V7 toggle — session-scoped via the existing {@link PortalAuthFilter}.
 * OFF by default; while off the full prompt array reaches the model unchanged.
 */
@RestController
@RequestMapping("/api/portal/developer/v7")
public class V7ToggleController {

    private final ContextMMU mmu;

    public V7ToggleController(ContextMMU mmu) {
        this.mmu = mmu;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return Map.of("enabled", mmu.enabledFor(dev(req)));
    }

    @PostMapping("/enable")
    public Map<String, Object> enable(HttpServletRequest req) {
        return Map.of("enabled", mmu.setEnabled(dev(req), true));
    }

    @PostMapping("/disable")
    public Map<String, Object> disable(HttpServletRequest req) {
        return Map.of("enabled", mmu.setEnabled(dev(req), false));
    }
}
