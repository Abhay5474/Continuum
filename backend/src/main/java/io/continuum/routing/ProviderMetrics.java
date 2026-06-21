package io.continuum.routing;

import io.continuum.persistence.entity.ProviderStatsEntity;
import io.continuum.persistence.repository.ProviderStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Records and serves real per-provider runtime statistics. Updated on every LLM
 * call by {@link io.continuum.provider.ProviderRouter}; read by the model router
 * to score providers. This is the "continuously learn from runtime history"
 * substrate — all values are measured, none are hardcoded.
 */
@Service
public class ProviderMetrics {

    private final ProviderStatsRepository repo;

    public ProviderMetrics(ProviderStatsRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void recordSuccess(String provider, long latencyMs, int promptTokens, int completionTokens, double cost) {
        ProviderStatsEntity s = repo.findById(provider).orElseGet(() -> new ProviderStatsEntity(provider));
        s.recordSuccess(latencyMs, promptTokens, completionTokens, cost);
        repo.save(s);
    }

    @Transactional
    public void recordFailure(String provider, long latencyMs) {
        ProviderStatsEntity s = repo.findById(provider).orElseGet(() -> new ProviderStatsEntity(provider));
        s.recordFailure(latencyMs);
        repo.save(s);
    }

    @Transactional(readOnly = true)
    public ProviderStatsEntity get(String provider) {
        return repo.findById(provider).orElseGet(() -> new ProviderStatsEntity(provider));
    }

    @Transactional(readOnly = true)
    public List<ProviderStatsEntity> all() {
        return repo.findAll();
    }
}
