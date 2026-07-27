package io.continuum.api;

import io.continuum.portal.PortalAuthFilter;
import io.continuum.specialist.SpecialistConnectionService;
import io.continuum.specialist.SpecialistProviders;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Specialist connections — a developer's credentials for third-party model
 * providers.
 *
 * <p>Tenant-scoped throughout, and no endpoint here returns a stored secret.
 */
@RestController
@RequestMapping("/api/portal/developer/specialists")
public class SpecialistConnectionController {

    private final SpecialistConnectionService connections;

    public SpecialistConnectionController(SpecialistConnectionService connections) {
        this.connections = connections;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    /** What can be connected to, and what each provider needs. */
    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        return SpecialistProviders.catalogue();
    }

    @GetMapping("/connections")
    public List<Map<String, Object>> list(HttpServletRequest req) {
        return connections.list(dev(req));
    }

    @PostMapping("/connections")
    public Map<String, Object> create(HttpServletRequest req, @RequestBody NewConnection body) {
        return connections.create(dev(req), body.name(), body.provider(), body.baseUrl(),
                body.authStyle(), body.authParam(), body.secret());
    }

    @PostMapping("/connections/{id}/secret")
    public Map<String, Object> rotate(HttpServletRequest req, @PathVariable Long id,
                                      @RequestBody SecretUpdate body) {
        return connections.rotateSecret(dev(req), id, body.secret());
    }

    @DeleteMapping("/connections/{id}")
    public Map<String, Object> delete(HttpServletRequest req, @PathVariable Long id) {
        connections.delete(dev(req), id);
        return Map.of("deleted", true);
    }

    public record NewConnection(String name, String provider, String baseUrl, String authStyle,
                                String authParam, String secret) {
    }

    public record SecretUpdate(String secret) {
    }
}
