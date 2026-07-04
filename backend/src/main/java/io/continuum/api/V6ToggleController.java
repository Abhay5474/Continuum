package io.continuum.api;

import io.continuum.dag.ConsensusDagService;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * V6 toggle — session-scoped to the authenticated developer via the existing
 * {@link PortalAuthFilter} (route lives under /api/portal/developer/**).
 * OFF by default; while off the gateway runs its exact legacy path.
 */
@RestController
@RequestMapping("/api/portal/developer/v6")
public class V6ToggleController {

    private final ConsensusDagService dag;

    public V6ToggleController(ConsensusDagService dag) {
        this.dag = dag;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return Map.of("enabled", dag.enabledFor(dev(req)));
    }

    @PostMapping("/enable")
    public Map<String, Object> enable(HttpServletRequest req) {
        return Map.of("enabled", dag.setEnabled(dev(req), true));
    }

    @PostMapping("/disable")
    public Map<String, Object> disable(HttpServletRequest req) {
        return Map.of("enabled", dag.setEnabled(dev(req), false));
    }
}
