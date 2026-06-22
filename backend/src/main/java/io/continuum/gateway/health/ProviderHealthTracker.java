package io.continuum.gateway.health;

import io.continuum.persistence.entity.ProviderModelHealthEntity;
import io.continuum.persistence.repository.ProviderModelHealthRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Tracks measured availability/latency/error-rate per (provider, model) so the
 * fallback policy can deprioritize unhealthy targets. Updated on every gateway
 * model attempt.
 */
@Service
public class ProviderHealthTracker {

    private final ProviderModelHealthRepository repo;

    public ProviderHealthTracker(ProviderModelHealthRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void recordSuccess(String provider, String model, long latencyMs) {
        ProviderModelHealthEntity h = repo.findById(ProviderModelHealthEntity.key(provider, model))
                .orElseGet(() -> new ProviderModelHealthEntity(provider, model));
        h.recordSuccess(latencyMs);
        repo.save(h);
    }

    @Transactional
    public void recordFailure(String provider, String model, long latencyMs, String error) {
        ProviderModelHealthEntity h = repo.findById(ProviderModelHealthEntity.key(provider, model))
                .orElseGet(() -> new ProviderModelHealthEntity(provider, model));
        h.recordFailure(latencyMs, error);
        repo.save(h);
    }

    @Transactional(readOnly = true)
    public double healthScore(String provider, String model) {
        return repo.findById(ProviderModelHealthEntity.key(provider, model))
                .map(ProviderModelHealthEntity::getHealthScore).orElse(1.0);
    }

    @Transactional(readOnly = true)
    public List<ProviderModelHealthEntity> all() {
        return repo.findAll();
    }
}
