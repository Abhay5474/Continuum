package io.continuum.registry;

import io.continuum.common.Json;
import io.continuum.persistence.entity.ModelEntity;
import io.continuum.persistence.repository.ModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maintains the model registry and lifecycle.
 *
 * Discovery reconciles each provider's catalog into the registry: net-new vetted
 * models become ACTIVE, net-new live-only models enter as DISCOVERED (never
 * auto-activated), and models that vanish from a provider are marked DEPRECATED
 * (model disappearance handling). Only ACTIVE models are eligible for routing.
 */
@Service
public class ModelRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryService.class);

    private final ModelRepository repo;
    private final List<ModelDiscoveryProvider> discoveries;
    private final Json json;

    public ModelRegistryService(ModelRepository repo, List<ModelDiscoveryProvider> discoveries, Json json) {
        this.repo = repo;
        this.discoveries = discoveries;
        this.json = json;
    }

    @Transactional
    public int discoverAll() {
        int changes = 0;
        for (ModelDiscoveryProvider d : discoveries) {
            changes += reconcile(d.provider(), d.discover());
        }
        return changes;
    }

    @Transactional
    public int reconcile(String provider, List<ModelDiscoveryProvider.DiscoveredModel> discovered) {
        int changes = 0;
        Set<String> seen = new HashSet<>();
        for (var dm : discovered) {
            seen.add(dm.modelName());
            ModelEntity existing = repo.findByProviderAndModelName(provider, dm.modelName()).orElse(null);
            String caps = json.write(dm.capabilities());
            String pricing = json.write(Map.of(
                    "costInputPer1k", dm.capabilities().costInputPer1k(),
                    "costOutputPer1k", dm.capabilities().costOutputPer1k()));
            if (existing == null) {
                ModelStatus initial = dm.vetted() ? ModelStatus.ACTIVE : ModelStatus.DISCOVERED;
                repo.save(new ModelEntity(provider, dm.modelName(), initial,
                        dm.capabilities().contextWindow(), caps, pricing));
                changes++;
                log.info("Registry: added {} {} as {}", provider, dm.modelName(), initial);
            } else {
                existing.setContextWindow(dm.capabilities().contextWindow());
                existing.setCapabilitiesJson(caps);
                existing.setPricingJson(pricing);
                existing.markChecked();
                // A previously REMOVED model that reappears returns to DISCOVERED for re-vetting.
                if (existing.getStatus() == ModelStatus.REMOVED) {
                    existing.setStatus(ModelStatus.DISCOVERED);
                    changes++;
                }
                repo.save(existing);
            }
        }
        // Disappearance: anything live-eligible that the provider no longer lists -> DEPRECATED.
        for (ModelEntity m : repo.findByProviderAndStatus(provider, ModelStatus.ACTIVE)) {
            if (!seen.contains(m.getModelName())) {
                m.setStatus(ModelStatus.DEPRECATED);
                repo.save(m);
                changes++;
                log.warn("Registry: {} {} disappeared from provider -> DEPRECATED", provider, m.getModelName());
            }
        }
        return changes;
    }

    @Transactional
    public ModelEntity transition(Long id, ModelStatus status) {
        ModelEntity m = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("No such model: " + id));
        m.setStatus(status);
        return repo.save(m);
    }

    @Transactional(readOnly = true)
    public List<ModelEntity> all() {
        return repo.findAll();
    }

    @Transactional(readOnly = true)
    public List<ModelEntity> active() {
        return repo.findByStatus(ModelStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<ModelEntity> activeFor(String provider) {
        return repo.findByProviderAndStatus(provider, ModelStatus.ACTIVE);
    }

    public ModelCapabilities capabilitiesOf(ModelEntity m) {
        if (m.getCapabilitiesJson() == null) {
            return new ModelCapabilities(m.getContextWindow(), false, true, true, 0, 0, "unknown");
        }
        return json.read(m.getCapabilitiesJson(), ModelCapabilities.class);
    }
}
