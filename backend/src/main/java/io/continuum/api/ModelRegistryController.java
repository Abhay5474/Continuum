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

    private final io.continuum.registry.catalog.ModelCatalogService catalogue;
    private final io.continuum.registry.catalog.ModelResolver resolver;

    public ModelRegistryController(ModelRegistryService registry,
                                   io.continuum.registry.catalog.ModelCatalogService catalogue,
                                   io.continuum.registry.catalog.ModelResolver resolver) {
        this.registry = registry;
        this.catalogue = catalogue;
        this.resolver = resolver;
    }

    @GetMapping
    public List<ModelEntity> all() {
        return registry.all();
    }

    @GetMapping("/active")
    public List<ModelEntity> active() {
        return registry.active();
    }

    /**
     * Re-run discovery: the built-in models, and a catalogue check of the real
     * providers (the same check as "Check now", with the same cooldown).
     */
    @PostMapping("/discover")
    public Map<String, Object> discover(HttpServletRequest req) {
        requireOperator(req);
        int changes = registry.discoverAll();
        var start = catalogue.requestCheck("operator");
        return Map.of("changes", changes, "check", start.outcome().name(), "message", start.message());
    }

    @PostMapping("/{id}/status")
    public ModelEntity transition(@PathVariable Long id, @RequestParam ModelStatus status,
                                  HttpServletRequest req) {
        requireOperator(req);
        ModelEntity m = registry.transition(id, status);
        resolver.refresh();
        return m;
    }

    private static void requireOperator(HttpServletRequest req) {
        if (!RequestScope.isOperator(req)) {
            throw new RequestScope.ForbiddenException();
        }
    }
}
