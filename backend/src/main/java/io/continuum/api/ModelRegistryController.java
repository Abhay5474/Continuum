package io.continuum.api;

import io.continuum.persistence.entity.ModelEntity;
import io.continuum.registry.ModelRegistryService;
import io.continuum.portal.RequestScope;
import io.continuum.registry.ModelStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Model registry, lifecycle and discovery.
 *
 * <p>The catalogue is engine-wide: every tenant routes against the same models,
 * so retiring one or re-running discovery changes what everybody gets. Reads are
 * open to any signed-in developer; changes belong to the operator.
 */
@RestController
@RequestMapping("/api/models")
public class ModelRegistryController {

    private final ModelRegistryService registry;

    public ModelRegistryController(ModelRegistryService registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<ModelEntity> all() {
        return registry.all();
    }

    @GetMapping("/active")
    public List<ModelEntity> active() {
        return registry.active();
    }

    @PostMapping("/discover")
    public Map<String, Object> discover(HttpServletRequest req) {
        requireOperator(req);
        return Map.of("changes", registry.discoverAll());
    }

    @PostMapping("/{id}/status")
    public ModelEntity transition(@PathVariable Long id, @RequestParam ModelStatus status,
                                  HttpServletRequest req) {
        requireOperator(req);
        return registry.transition(id, status);
    }

    private static void requireOperator(HttpServletRequest req) {
        if (!RequestScope.isOperator(req)) {
            throw new RequestScope.ForbiddenException();
        }
    }
}
