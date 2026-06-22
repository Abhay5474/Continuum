package io.continuum.portal;

import io.continuum.persistence.entity.DeveloperAuthEntity;
import io.continuum.persistence.entity.DeveloperEntity;
import io.continuum.persistence.repository.DeveloperAuthRepository;
import io.continuum.persistence.repository.DeveloperRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Self-service developer portal: signup, login, and per-developer routing
 * preference. Issues HMAC-signed session tokens so a developer can manage their
 * own keys/credentials without the platform admin token.
 */
@Service
public class PortalService {

    private final DeveloperRepository developers;
    private final DeveloperAuthRepository auth;
    private final PasswordHasher passwordHasher;
    private final PortalSessionService sessions;

    public PortalService(DeveloperRepository developers, DeveloperAuthRepository auth,
                         PasswordHasher passwordHasher, PortalSessionService sessions) {
        this.developers = developers;
        this.auth = auth;
        this.passwordHasher = passwordHasher;
        this.sessions = sessions;
    }

    @Transactional
    public LoginResult signup(String name, String email, String password) {
        if (email == null || email.isBlank() || password == null || password.length() < 6) {
            throw new IllegalArgumentException("Email and a password of at least 6 characters are required");
        }
        if (developers.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("An account with this email already exists");
        }
        DeveloperEntity dev = developers.save(new DeveloperEntity(name == null ? email : name, email));
        auth.save(new DeveloperAuthEntity(dev.getId(), passwordHasher.hash(password)));
        return new LoginResult(dev.getId(), dev.getName(), dev.getEmail(),
                sessions.issue(dev.getId(), PortalSessionService.Role.DEVELOPER));
    }

    @Transactional(readOnly = true)
    public LoginResult login(String email, String password) {
        Optional<DeveloperEntity> dev = developers.findFirstByEmailIgnoreCase(email);
        if (dev.isPresent()) {
            DeveloperAuthEntity a = auth.findById(dev.get().getId()).orElse(null);
            if (a != null && passwordHasher.matches(password, a.getPasswordHash())) {
                return new LoginResult(dev.get().getId(), dev.get().getName(), dev.get().getEmail(),
                        sessions.issue(dev.get().getId(), PortalSessionService.Role.DEVELOPER));
            }
        }
        throw new IllegalArgumentException("Invalid email or password");
    }

    @Transactional(readOnly = true)
    public boolean useOwnKeysPrimary(String developerId) {
        return auth.findById(developerId).map(DeveloperAuthEntity::isUseOwnKeysPrimary).orElse(true);
    }

    @Transactional
    public void setUseOwnKeysPrimary(String developerId, boolean value) {
        DeveloperAuthEntity a = auth.findById(developerId)
                .orElseThrow(() -> new IllegalArgumentException("No auth record for developer"));
        a.setUseOwnKeysPrimary(value);
        auth.save(a);
    }

    public record LoginResult(String developerId, String name, String email, String sessionToken) {
    }
}
