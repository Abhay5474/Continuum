package io.continuum.api;

import io.continuum.portal.PortalAuthFilter;
import io.continuum.saga.SagaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Saga compensation. Tenant-scoped; OFF by default. */
@RestController
@RequestMapping("/api/portal/developer/saga")
public class SagaController {

    private final SagaService saga;

    public SagaController(SagaService saga) {
        this.saga = saga;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return saga.status(dev(req));
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest req, @RequestBody Settings body) {
        return saga.configure(dev(req), body.enabled());
    }

    @DeleteMapping
    public Map<String, Object> clear(HttpServletRequest req) {
        return saga.clear(dev(req));
    }

    public record Settings(Boolean enabled) {
    }
}
