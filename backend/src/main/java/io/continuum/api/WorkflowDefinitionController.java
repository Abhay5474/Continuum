package io.continuum.api;

import io.continuum.declarative.DefinitionService;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Customer-defined workflows — publish a declarative graph, then run it on the
 * durable engine. Session-scoped, so a definition belongs to the developer who
 * created it.
 */
@RestController
@RequestMapping("/api/portal/developer/workflows/definitions")
public class WorkflowDefinitionController {

    private final DefinitionService definitions;

    public WorkflowDefinitionController(DefinitionService definitions) {
        this.definitions = definitions;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req) {
        return definitions.list(dev(req));
    }

    @GetMapping("/{name}")
    public Map<String, Object> get(HttpServletRequest req, @PathVariable String name,
                                   @RequestParam(required = false) Integer version) {
        return definitions.get(dev(req), name, version);
    }

    /** Publishes a new version. Validation failures come back as 400 with the reason. */
    @PostMapping("/{name}")
    public Map<String, Object> publish(HttpServletRequest req, @PathVariable String name,
                                       @RequestBody Map<String, Object> spec) {
        var saved = definitions.publish(dev(req), name, spec);
        return Map.of("name", saved.getName(), "version", saved.getVersion(), "published", true);
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(HttpServletRequest req, @PathVariable String name) {
        definitions.delete(dev(req), name);
        return Map.of("deleted", true, "name", name);
    }

    /** Starts a durable run. Pass a workflowId to make the start idempotent. */
    @PostMapping("/{name}/run")
    public Map<String, Object> run(HttpServletRequest req, @PathVariable String name,
                                   @RequestParam(required = false) Integer version,
                                   @RequestBody(required = false) RunRequest body) {
        return definitions.run(dev(req), name, version,
                body == null ? Map.of() : body.input(),
                body == null ? null : body.workflowId());
    }

    public record RunRequest(Map<String, Object> input, String workflowId) {
    }
}
