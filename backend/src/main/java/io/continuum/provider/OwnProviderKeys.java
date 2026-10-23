package io.continuum.provider;

import io.continuum.vault.CredentialVaultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The model-provider keys an account saved under API Keys &amp; Providers, for
 * work Continuum does on that account's behalf outside a chat request.
 *
 * <p>The gateway always used them. The verification engine's agents called
 * the providers with the server's keys only, so on a deployment started
 * without GEMINI_API_KEY / GROQ_API_KEY — keys kept in the console instead —
 * every verification failed and the request quietly fell back to the plain
 * path. Read when needed and never stored, so no key reaches a workflow's
 * history.
 */
@Component
public class OwnProviderKeys {

    private static final Logger log = LoggerFactory.getLogger(OwnProviderKeys.class);

    private final CredentialVaultService vault;
    private final ProviderRouter router;

    public OwnProviderKeys(CredentialVaultService vault, ProviderRouter router) {
        this.vault = vault;
        this.router = router;
    }

    /** provider → key, for the providers this account saved a key for; empty when none or unknown. */
    public Map<String, String> forDeveloper(String developerId) {
        Map<String, String> keys = new LinkedHashMap<>();
        if (developerId == null) {
            return keys;
        }
        try {
            for (CredentialVaultService.CredentialInfo c : vault.listProviders(developerId)) {
                if (router.isProvider(c.provider())) {
                    vault.decrypt(developerId, c.provider()).filter(k -> !k.isBlank())
                            .ifPresent(k -> keys.put(c.provider(), k));
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not read the saved provider keys of {}: {}", developerId, e.getMessage());
        }
        return keys;
    }
}
