package io.continuum.api;

import io.continuum.portal.PortalAuthFilter;
import io.continuum.specialist.SpecialistConnectionService;
import io.continuum.specialist.SpecialistProviders;
import io.continuum.specialist.SpecialistService;
import io.continuum.specialist.TraceRecorder;
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
    private final SpecialistService specialists;
    private final TraceRecorder traces;

    public SpecialistConnectionController(SpecialistConnectionService connections,
                                          SpecialistService specialists, TraceRecorder traces) {
        this.connections = connections;
        this.specialists = specialists;
        this.traces = traces;
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

    // --- specialists --------------------------------------------------------

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req, @RequestParam(required = false) String all) {
        return specialists.list(dev(req));
    }

    @PostMapping
    public Map<String, Object> create(HttpServletRequest req, @RequestBody NewSpecialist body) {
        return specialists.create(dev(req), body.connectionId(), body.name(), body.modelPath(),
                body.inputKind(), body.minConfidence(), body.timeoutSeconds());
    }

    @PutMapping("/{id}")
    public Map<String, Object> configure(HttpServletRequest req, @PathVariable Long id,
                                         @RequestBody SpecialistSettings body) {
        return specialists.configure(dev(req), id, body.minConfidence(), body.timeoutSeconds());
    }

    /**
     * Sends one real request and records the answer.
     *
     * <p>This is what turns a DRAFT specialist into a usable one, and what the
     * console shows instead of describing the integration.
     */
    @PostMapping("/{id}/probe")
    public Map<String, Object> probe(HttpServletRequest req, @PathVariable Long id,
                                     @RequestBody(required = false) Map<String, Object> sample) {
        return specialists.probe(dev(req), id, sample);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteSpecialist(HttpServletRequest req, @PathVariable Long id) {
        specialists.delete(dev(req), id);
        return Map.of("deleted", true);
    }

    // --- trace --------------------------------------------------------------

    /** The visible chain behind one answer. */
    @GetMapping("/traces/{traceId}")
    public Map<String, Object> trace(HttpServletRequest req, @PathVariable String traceId) {
        return traces.trace(dev(req), traceId);
    }

    @GetMapping("/traces")
    public List<String> recentTraces(HttpServletRequest req,
                                     @RequestParam(defaultValue = "20") int limit) {
        return traces.recent(dev(req), limit);
    }

    public record NewConnection(String name, String provider, String baseUrl, String authStyle,
                                String authParam, String secret) {
    }

    public record NewSpecialist(Long connectionId, String name, String modelPath, String inputKind,
                                Double minConfidence, Integer timeoutSeconds) {
    }

    public record SpecialistSettings(Double minConfidence, Integer timeoutSeconds) {
    }

    public record SecretUpdate(String secret) {
    }
}
