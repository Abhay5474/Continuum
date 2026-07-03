package io.continuum.autopilot;

import io.continuum.autopilot.model.PolicyBundle;
import io.continuum.persistence.entity.AutopilotConfigEntity;
import io.continuum.persistence.repository.AutopilotConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The single, safe integration point between Autopilot and the gateway.
 *
 * Returns the effective policy for a developer's request ONLY when Autopilot is
 * enabled AND an active bundle exists. When Autopilot is disabled (the default),
 * or no bundle has been activated, it returns empty and the gateway runs its
 * exact pre-Autopilot path — guaranteeing identical behaviour when off.
 *
 * When a canary is running, a bounded random fraction of requests are routed
 * through the candidate bundle so its metrics can be compared to the baseline.
 */
@Service
public class PolicyResolver {

    private final AutopilotConfigRepository configRepo;
    private final PolicyBundleService bundles;

    public PolicyResolver(AutopilotConfigRepository configRepo, PolicyBundleService bundles) {
        this.configRepo = configRepo;
        this.bundles = bundles;
    }

    public record ResolvedPolicy(PolicyBundle policy, long bundleId, boolean canary) {
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedPolicy> resolve(String developerId) {
        AutopilotConfigEntity config = configRepo.findById(developerId).orElse(null);
        if (config == null || !config.isEnabled() || config.getActiveBundleId() == null) {
            return Optional.empty(); // ← exact pre-Autopilot behaviour
        }

        // Canary split: route a fraction of traffic through the candidate.
        Long canaryId = config.getCanaryBundleId();
        if (canaryId != null) {
            var canaryBundle = bundles.bundle(canaryId);
            if (canaryBundle.isPresent()) {
                int pct = Math.max(0, Math.min(100, canaryBundle.get().canaryPercentage()));
                if (ThreadLocalRandom.current().nextInt(100) < pct) {
                    return Optional.of(new ResolvedPolicy(canaryBundle.get(), canaryId, true));
                }
            }
        }

        return bundles.bundle(config.getActiveBundleId())
                .map(b -> new ResolvedPolicy(b, config.getActiveBundleId(), false));
    }
}
