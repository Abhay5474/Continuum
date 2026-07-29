package io.continuum.specialist;

import io.continuum.persistence.entity.SpecialistConnectionEntity;
import io.continuum.persistence.repository.SpecialistConnectionRepository;
import io.continuum.vault.CredentialVaultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages a developer's credentials for third-party model providers.
 *
 * <p>Bring-your-own-key, deliberately. Continuum holding one Roboflow account
 * and billing every customer's inference to it would be a cost liability, a
 * rate-limit shared across strangers, and a data-handling promise Continuum
 * cannot make on the provider's behalf. The credential belongs to the
 * developer's workspace; Continuum only stores and presents it.
 *
 * <p>The secret goes into the existing AES-GCM vault under a namespaced key, so
 * it can never collide with an LLM provider credential and is decrypted only at
 * the moment of a call. Nothing in this class returns a secret — not to the
 * console, not in a log, not in an error.
 */
@Service
public class SpecialistConnectionService {

    private static final Logger log = LoggerFactory.getLogger(SpecialistConnectionService.class);

    /** Vault namespace, so a connection secret cannot collide with a provider key. */
    private static final String VAULT_PREFIX = "specialist:";

    private final SpecialistConnectionRepository repo;
    private final CredentialVaultService vault;

    public SpecialistConnectionService(SpecialistConnectionRepository repo, CredentialVaultService vault) {
        this.repo = repo;
        this.vault = vault;
    }

    /** Raised when a connection is misconfigured; the message reaches the developer. */
    public static class InvalidConnectionException extends IllegalArgumentException {
        public InvalidConnectionException(String message) {
            super(message);
        }
    }

    @Transactional
    public Map<String, Object> create(String developerId, String name, String provider, String baseUrl,
                                      String authStyle, String authParam, String secret) {
        if (name == null || name.isBlank()) {
            throw new InvalidConnectionException("A connection needs a name.");
        }
        if (repo.findByDeveloperIdAndName(developerId, name).isPresent()) {
            throw new InvalidConnectionException("You already have a connection called '" + name + "'.");
        }
        SpecialistProvider adapter = SpecialistProviders.byName(provider);

        SpecialistConnectionEntity.AuthStyle style;
        try {
            style = authStyle == null || authStyle.isBlank()
                    ? adapter.defaultAuthStyle()
                    : SpecialistConnectionEntity.AuthStyle.valueOf(authStyle.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidConnectionException("Unknown auth style: " + authStyle);
        }

        String effectiveBase = baseUrl == null || baseUrl.isBlank() ? adapter.defaultBaseUrl() : baseUrl.strip();
        if (effectiveBase == null || effectiveBase.isBlank()) {
            throw new InvalidConnectionException(
                    "Provider '" + provider + "' has no fixed endpoint, so a base URL is required.");
        }
        if (style != SpecialistConnectionEntity.AuthStyle.NONE && (secret == null || secret.isBlank())) {
            throw new InvalidConnectionException("This connection needs a credential.");
        }
        // Fall back to the adapter's own parameter name BEFORE validating: a
        // provider that already knows it wants `api_key` should not make the
        // developer type it.
        String effectiveParam = authParam == null || authParam.isBlank()
                ? adapter.defaultAuthParam() : authParam.strip();
        if ((style == SpecialistConnectionEntity.AuthStyle.HEADER
                || style == SpecialistConnectionEntity.AuthStyle.QUERY)
                && (effectiveParam == null || effectiveParam.isBlank())) {
            throw new InvalidConnectionException(
                    "A " + style + " credential needs the name of the header or query parameter.");
        }

        SpecialistConnectionEntity saved = repo.save(new SpecialistConnectionEntity(
                developerId, name.strip(), adapter.name(), effectiveBase, style, effectiveParam));

        if (secret != null && !secret.isBlank()) {
            String ref = VAULT_PREFIX + saved.getId();
            vault.store(developerId, ref, secret);
            saved.setCredentialRef(ref);
            repo.save(saved);
        }
        return describe(saved);
    }

    /** Replaces the secret without disturbing anything else about the connection. */
    @Transactional
    public Map<String, Object> rotateSecret(String developerId, Long id, String secret) {
        SpecialistConnectionEntity c = require(developerId, id);
        if (secret == null || secret.isBlank()) {
            throw new InvalidConnectionException("A replacement credential is required.");
        }
        String ref = c.getCredentialRef() == null ? VAULT_PREFIX + c.getId() : c.getCredentialRef();
        vault.store(developerId, ref, secret);
        c.setCredentialRef(ref);
        // A rotated credential has not been proved to work; say so rather than
        // inheriting the old one's verified status.
        c.markFailed(null);
        repo.save(c);
        return describe(c);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String developerId) {
        return repo.findByDeveloperIdOrderByNameAsc(developerId).stream().map(this::describe).toList();
    }

    @Transactional(readOnly = true)
    public SpecialistConnectionEntity require(String developerId, Long id) {
        return repo.findByIdAndDeveloperId(id, developerId)
                .orElseThrow(() -> new InvalidConnectionException("No such connection."));
    }

    /**
     * The decrypted secret, for use at call time only.
     *
     * <p>Package-visible on purpose: nothing outside the specialist execution
     * path has a legitimate reason to hold one.
     */
    Optional<String> secretFor(SpecialistConnectionEntity c) {
        if (c.getCredentialRef() == null) {
            return Optional.empty();
        }
        return vault.decrypt(c.getDeveloperId(), c.getCredentialRef());
    }

    /**
     * The tenant's stored key for a provider, for provider <em>discovery</em>.
     *
     * <p>Exists so discovery reuses the credential the tenant already has rather
     * than asking for a second one and storing it somewhere new. It resolves
     * through the same vault and the same {@code credentialRef} convention as
     * {@link #secretFor}; there is deliberately no second key store.
     *
     * <p>The returned value is a live secret. It may be handed to the provider
     * it belongs to and nowhere else — never to a response body, a log line, a
     * trace, an error message or a model prompt.
     */
    @Transactional(readOnly = true)
    public Optional<String> discoveryKeyFor(String developerId, String provider) {
        if (developerId == null || provider == null) {
            return Optional.empty();
        }
        for (SpecialistConnectionEntity c : repo.findByDeveloperIdOrderByNameAsc(developerId)) {
            if (provider.equalsIgnoreCase(c.getProvider())) {
                Optional<String> secret = secretFor(c);
                if (secret.isPresent() && !secret.get().isBlank()) {
                    return secret;
                }
            }
        }
        return Optional.empty();
    }

    @Transactional
    public void recordSuccess(SpecialistConnectionEntity c) {
        c.markVerified();
        repo.save(c);
    }

    @Transactional
    public void recordFailure(SpecialistConnectionEntity c, String error) {
        c.markFailed(error);
        repo.save(c);
    }

    @Transactional
    public void delete(String developerId, Long id) {
        SpecialistConnectionEntity c = require(developerId, id);
        if (c.getCredentialRef() != null) {
            try {
                vault.delete(developerId, c.getCredentialRef());
            } catch (Exception e) {
                // A vault row we could not remove must not block deleting the
                // connection; log it rather than stranding the developer.
                log.warn("Could not delete vault entry {} for developer {}: {}",
                        c.getCredentialRef(), developerId, e.getMessage());
            }
        }
        repo.delete(c);
    }

    /** Console representation. Never includes the secret. */
    public Map<String, Object> describe(SpecialistConnectionEntity c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("provider", c.getProvider());
        m.put("baseUrl", c.getBaseUrl());
        m.put("authStyle", c.getAuthStyle().name());
        m.put("authParam", c.getAuthParam());
        m.put("hasCredential", c.getCredentialRef() != null);
        m.put("status", c.getStatus().name());
        m.put("lastError", c.getLastError());
        m.put("verifiedAt", c.getVerifiedAt());
        m.put("createdAt", c.getCreatedAt());
        return m;
    }
}
