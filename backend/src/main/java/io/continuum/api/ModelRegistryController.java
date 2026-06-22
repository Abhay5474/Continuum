package io.continuum.api;

import io.continuum.persistence.entity.ModelEntity;
import io.continuum.registry.ModelRegistryService;
import io.continuum.registry.ModelStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Features 6 & 7 — model registry, lifecycle and discovery. */
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
    public Map<String, Object> discover() {
        return Map.of("changes", registry.discoverAll());
    }

    @PostMapping("/{id}/status")
    public ModelEntity transition(@PathVariable Long id, @RequestParam ModelStatus status) {
        return registry.transition(id, status);
    }
}
