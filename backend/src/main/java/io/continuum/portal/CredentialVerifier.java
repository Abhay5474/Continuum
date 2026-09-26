package io.continuum.portal;

import io.continuum.persistence.entity.ModelEntity;
import io.continuum.provider.ProviderRouter;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import io.continuum.registry.ModelRegistryService;
import io.continuum.vault.CredentialVaultService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Background pre-validation of a stored provider credential: makes a tiny,
 * cheapest-model call with the developer's decrypted key to confirm it actually
 * works before they wire it into production. Powers the UI "verified" indicator.
 */
@Service
public class CredentialVerifier {

    private final CredentialVaultService vault;
    private final ModelRegistryService registry;
    private final ProviderRouter router;

    public CredentialVerifier(CredentialVaultService vault, ModelRegistryService registry, ProviderRouter router) {
        this.vault = vault;
        this.registry = registry;
        this.router = router;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<io.continuum.registry.catalog.ModelResolver> resolver;

    public Result verify(String developerId, String provider) {
        String key = vault.decrypt(developerId, provider).orElse(null);
        if (key == null) {
            return new Result(false, "No credential stored for " + provider);
        }
        // Probe with the provider's current default — the model the catalogue has
        // seen answer — falling back to any active model for this provider.
        io.continuum.registry.catalog.ModelResolver r = resolver == null ? null : resolver.getIfAvailable();
        String preferred = r == null ? null : r.defaultFor(provider);
        List<ModelEntity> active = registry.activeFor(provider);
        String model = preferred != null && active.stream().anyMatch(m -> m.getModelName().equals(preferred))
                ? preferred
                : active.stream()
                        .min((a, b) -> Integer.compare(a.getContextWindow(), b.getContextWindow()))
                        .map(ModelEntity::getModelName).orElse(preferred);
        if (model == null) {
            return new Result(false, "No active model registered for " + provider);
        }
        try {
            LlmRequest probe = new LlmRequest(model, List.of(Message.user("ping")), 1, 0.0);
            router.complete(probe, List.of(provider), Map.of(provider, key));
            return new Result(true, "Key verified against " + provider + "/" + model);
        } catch (Exception e) {
            return new Result(false, "Verification failed: " + e.getMessage());
        }
    }

    public record Result(boolean valid, String message) {
    }
}
