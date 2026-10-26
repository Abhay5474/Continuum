package io.continuum.developer;

import io.continuum.persistence.entity.AppEntity;
import io.continuum.persistence.entity.DeveloperApiKeyEntity;
import io.continuum.persistence.entity.DeveloperEntity;
import io.continuum.persistence.repository.AppRepository;
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

    /** Optional so unit tests can build the service by hand. */
    private io.continuum.portal.EmailLock emailLock;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEmailLock(io.continuum.portal.EmailLock emailLock) {
        this.emailLock = emailLock;
    }

    /** Optional so unit tests can build the service by hand; keys then belong to no app. */
    private AppRepository apps;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setApps(AppRepository apps) {
        this.apps = apps;
    }

    public DeveloperService(DeveloperRepository developers, DeveloperApiKeyRepository keys, ApiKeyHasher hasher) {
        this.developers = developers;
        this.keys = keys;
        this.hasher = hasher;
    }

    @Transactional
    public DeveloperEntity createDeveloper(String name, String email) {
        // The admin path never checked at all, so it could mint a second
        // account for an address that already had one.
        if (emailLock != null) {
            emailLock.lock(email);
        }
        if (email != null && developers.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("An account with this email already exists");
        }
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
        return issueKey(developerId, null);
    }

    @Transactional
    public IssuedKey issueKey(String developerId, String label) {
        return issueKey(developerId, label, null);
    }

    /**
     * Issues a key for an app.
     *
     * @param appId the app the key joins, so a key replacing a leaked one keeps
     *              that app's history, memory and cache; {@code null} starts a
     *              new app, named after the label
     */
    @Transactional
    public IssuedKey issueKey(String developerId, String label, Long appId) {
        if (developers.findById(developerId).isEmpty()) {
            throw new IllegalArgumentException("No such developer: " + developerId);
        }
        String name = cleanName(label);
        ApiKeyHasher.GeneratedKey g = hasher.generate();
        DeveloperApiKeyEntity entity = new DeveloperApiKeyEntity(developerId, g.prefix(), g.hash());
        AppEntity app = appFor(developerId, name, appId);
        if (name != null) {
            entity.setLabel(name);
        } else if (app != null) {
            entity.setLabel(app.getName());
        }
        entity.setAppId(app == null ? null : app.getId());
        entity = keys.save(entity);
        return new IssuedKey(entity.getId(), g.plaintext());
    }

    private AppEntity appFor(String developerId, String name, Long appId) {
        if (apps == null) {
            return null;
        }
        if (appId != null) {
            return requireApp(developerId, appId);
        }
        return apps.save(new AppEntity(developerId,
                name != null ? name : "App " + (apps.countByDeveloperId(developerId) + 1)));
    }

    /** The account's apps, oldest first. */
    @Transactional(readOnly = true)
    public List<AppEntity> appsFor(String developerId) {
        return apps == null ? List.of() : apps.findByDeveloperIdOrderByCreatedAtAsc(developerId);
    }

    @Transactional(readOnly = true)
    public AppEntity requireApp(String developerId, Long appId) {
        if (apps == null || appId == null) {
            throw new io.continuum.portal.RequestScope.NotFoundException("No such app.");
        }
        return apps.findByIdAndDeveloperId(appId, developerId)
                .orElseThrow(() -> new io.continuum.portal.RequestScope.NotFoundException("No such app."));
    }

    /**
     * Renames an app. Its keys carry the name too, so the key list and the app
     * selector never disagree.
     */
    @Transactional
    public AppEntity renameApp(String developerId, Long appId, String name) {
        String clean = cleanName(name);
        if (clean == null) {
            throw new IllegalArgumentException("A name is required.");
        }
        AppEntity app = requireApp(developerId, appId);
        app.setName(clean);
        apps.save(app);
        for (DeveloperApiKeyEntity k : keys.findByDeveloperId(developerId)) {
            if (appId.equals(k.getAppId())) {
                k.setLabel(clean);
                keys.save(k);
            }
        }
        return app;
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String clean = name.strip();
        if (clean.length() > 120) {
            throw new IllegalArgumentException("A name is limited to 120 characters.");
        }
        return clean;
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
        return authenticateKey(plaintextKey).map(Authenticated::developer);
    }

    /** As {@link #authenticate}, also naming the key and the app it belongs to. */
    @Transactional
    public Optional<Authenticated> authenticateKey(String plaintextKey) {
        String prefix = hasher.prefixOf(plaintextKey);
        if (prefix == null) {
            return Optional.empty();
        }
        for (DeveloperApiKeyEntity key : keys.findByKeyPrefix(prefix)) {
            if (key.isActive() && hasher.matches(plaintextKey, key.getHashedKey())) {
                key.touch();
                keys.save(key);
                return developers.findById(key.getDeveloperId())
                        .map(d -> new Authenticated(d, key.getId(), key.getAppId()));
            }
        }
        return Optional.empty();
    }

    public record IssuedKey(Long id, String plaintextKey) {
    }

    /** A key that checked out: whose it is, which key, and which app it speaks for. */
    public record Authenticated(DeveloperEntity developer, Long keyId, Long appId) {
    }
}
