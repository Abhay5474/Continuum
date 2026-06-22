package io.continuum.developer;

import io.continuum.persistence.entity.DeveloperApiKeyEntity;
import io.continuum.persistence.entity.DeveloperEntity;
import io.continuum.persistence.repository.DeveloperApiKeyRepository;
import io.continuum.persistence.repository.DeveloperRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Manages developer accounts and their Continuum API keys. */
@Service
public class DeveloperService {

    private final DeveloperRepository developers;
    private final DeveloperApiKeyRepository keys;
    private final ApiKeyHasher hasher;

    public DeveloperService(DeveloperRepository developers, DeveloperApiKeyRepository keys, ApiKeyHasher hasher) {
        this.developers = developers;
        this.keys = keys;
        this.hasher = hasher;
    }

    @Transactional
    public DeveloperEntity createDeveloper(String name, String email) {
        return developers.save(new DeveloperEntity(name, email));
    }

    @Transactional(readOnly = true)
    public Optional<DeveloperEntity> find(String id) {
        return developers.findById(id);
    }

    @Transactional(readOnly = true)
    public List<DeveloperEntity> all() {
        return developers.findAll();
    }

    /** Issue a new API key. The returned plaintext is shown ONCE and never stored. */
    @Transactional
    public IssuedKey issueKey(String developerId) {
        if (developers.findById(developerId).isEmpty()) {
            throw new IllegalArgumentException("No such developer: " + developerId);
        }
        ApiKeyHasher.GeneratedKey g = hasher.generate();
        DeveloperApiKeyEntity entity = keys.save(
                new DeveloperApiKeyEntity(developerId, g.prefix(), g.hash()));
        return new IssuedKey(entity.getId(), g.plaintext());
    }

    @Transactional
    public boolean revokeKey(Long keyId) {
        return keys.findById(keyId).map(k -> {
            k.revoke();
            keys.save(k);
            return true;
        }).orElse(false);
    }

    @Transactional(readOnly = true)
    public List<DeveloperApiKeyEntity> keysFor(String developerId) {
        return keys.findByDeveloperId(developerId);
    }

    /**
     * Authenticate a presented API key. Looks up by non-secret prefix, then does
     * a constant-time hash comparison; returns the owning developer if valid and
     * active.
     */
    @Transactional
    public Optional<DeveloperEntity> authenticate(String plaintextKey) {
        String prefix = hasher.prefixOf(plaintextKey);
        if (prefix == null) {
            return Optional.empty();
        }
        for (DeveloperApiKeyEntity key : keys.findByKeyPrefix(prefix)) {
            if (key.isActive() && hasher.matches(plaintextKey, key.getHashedKey())) {
                key.touch();
                keys.save(key);
                return developers.findById(key.getDeveloperId());
            }
        }
        return Optional.empty();
    }

    public record IssuedKey(Long id, String plaintextKey) {
    }
}
