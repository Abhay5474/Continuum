package io.continuum.portal;

import io.continuum.persistence.entity.DeveloperAuthEntity;
import io.continuum.persistence.entity.OperatorGrantEntity;
import io.continuum.persistence.repository.DeveloperAuthRepository;
import io.continuum.persistence.repository.DeveloperRepository;
import io.continuum.persistence.repository.OperatorGrantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who may operate the engine, without a shared secret.
 *
 * <p>Operator access used to mean "whoever knows {@code CONTINUUM_ADMIN_TOKEN}":
 * one string in the server's environment, shared by everyone who operates it,
 * never rotated, and naming nobody in particular. That still works — it is the
 * break-glass path — but the everyday path is now a role on a person's account:
 *
 * <ul>
 *   <li><b>Bootstrap.</b> While nobody holds the role, the server makes a one-time
 *       128-bit setup code at start-up and writes it only to its own log. The
 *       first signed-in person to enter it becomes an operator; the code then
 *       stops working. Reading the server log is the proof of ownership, the same
 *       trust a first-run installer relies on.</li>
 *   <li><b>Step-up.</b> An operator unlocks engine-wide controls by re-entering
 *       their password, which yields a 30-minute operator session. A stolen
 *       everyday session is therefore not an operator session.</li>
 *   <li><b>Revocation.</b> Removing a grant, changing the password or signing out
 *       everywhere ends that person's operator sessions within seconds.</li>
 *   <li><b>Delegation.</b> Operators grant and remove the role by email, with
 *       their password; the last operator cannot be removed, so the deployment
 *       is never left without one.</li>
 * </ul>
 */
@Service
public class OperatorService {

    private static final Logger log = LoggerFactory.getLogger(OperatorService.class);

    private static final long SETUP_CODE_TTL_SECONDS = 24 * 3600;
    private static final long GRANT_CACHE_MS = 15_000;
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I

    private final OperatorGrantRepository grants;
    private final DeveloperRepository developers;
    private final DeveloperAuthRepository auth;
    private final PasswordHasher passwords;
    private final PortalSessionService sessions;
    private final RateLimiter limiter;
    private final SecureRandom random = new SecureRandom();

    /** SHA-256 of the current setup code; the code itself is never kept. */
    private volatile byte[] setupCodeHash;
    private volatile Instant setupCodeExpires;

    private record Cached(boolean granted, long readAt) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public OperatorService(OperatorGrantRepository grants, DeveloperRepository developers,
                           DeveloperAuthRepository auth, PasswordHasher passwords,
                           PortalSessionService sessions, RateLimiter limiter) {
        this.grants = grants;
        this.developers = developers;
        this.auth = auth;
        this.passwords = passwords;
        this.sessions = sessions;
        this.limiter = limiter;
        sessions.setOperatorGrants(this::isOperator);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (grants.count() == 0) {
            newSetupCode();
        }
    }

    /** Makes a fresh setup code and writes it to the log — the only place it appears. */
    synchronized void newSetupCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 26; i++) { // 26 symbols of 5 bits: 130 bits
            if (i > 0 && i % 5 == 0) {
                code.append('-');
            }
            code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        setupCodeHash = sha256(normalise(code.toString()));
        setupCodeExpires = Instant.now().plusSeconds(SETUP_CODE_TTL_SECONDS);
        log.warn("""

                ================================================================
                 No one operates this Continuum deployment yet.
                 Sign in to the console, open "Operator access" and enter
                 this one-time setup code (valid 24 hours, single use):

                     {}

                 Whoever enters it becomes the first operator.
                ================================================================""", code);
    }

    public boolean isOperator(String developerId) {
        if (developerId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Cached c = cache.get(developerId);
        if (c == null || now - c.readAt() > GRANT_CACHE_MS) {
            c = new Cached(grants.existsById(developerId), now);
            if (cache.size() > 10_000) {
                cache.clear();
            }
            cache.put(developerId, c);
        }
        return c.granted();
    }

    public boolean anyOperator() {
        return grants.count() > 0;
    }

    /** What the console needs to decide which door to show. */
    public Map<String, Object> status(String actor) {
        boolean any = anyOperator();
        return Map.of(
                "operator", isOperator(actor),
                "anyOperator", any,
                "setupOpen", !any && setupCodeHash != null && Instant.now().isBefore(setupCodeExpires),
                "sessionMinutes", PortalSessionService.OPERATOR_TTL_SECONDS / 60);
    }

    /** First operator: the person who can read the server log. */
    @Transactional
    public synchronized String claim(String actor, String code) {
        throttle("operator-claim:" + actor);
        if (anyOperator()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This deployment already has an operator. Ask them to grant you access.");
        }
        byte[] expected = setupCodeHash;
        if (expected == null || Instant.now().isAfter(setupCodeExpires)) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "The setup code has expired. Restart the server to print a new one.");
        }
        if (code == null || !MessageDigest.isEqual(expected, sha256(normalise(code)))) {
            // 400, not 401: a 401 means "your session is gone" and signs the console out.
            throw new IllegalArgumentException("That setup code is not right.");
        }
        grants.save(new OperatorGrantEntity(actor, null));
        setupCodeHash = null; // single use
        cache.remove(actor);
        log.info("Developer {} claimed the operator role with the setup code", actor);
        return sessions.issueOperator(actor);
    }

    /** Password step-up: an everyday session plus the password buys a short operator session. */
    public String elevate(String actor, String password) {
        if (!isOperator(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your account does not have operator access.");
        }
        checkPassword(actor, password);
        return sessions.issueOperator(actor);
    }

    public List<Map<String, Object>> list() {
        return grants.findAll().stream()
                .sorted((a, b) -> a.getGrantedAt().compareTo(b.getGrantedAt()))
                .map(g -> {
                    var dev = developers.findById(g.getDeveloperId());
                    return Map.<String, Object>of(
                            "developerId", g.getDeveloperId(),
                            "name", dev.map(d -> d.getName() == null ? "" : d.getName()).orElse(""),
                            "email", dev.map(d -> d.getEmail() == null ? "" : d.getEmail()).orElse(""),
                            "grantedBy", g.getGrantedBy() == null ? "setup code" : g.getGrantedBy(),
                            "grantedAt", g.getGrantedAt().toString());
                })
                .toList();
    }

    @Transactional
    public Map<String, Object> grant(String actor, String password, String email) {
        requireOperator(actor);
        checkPassword(actor, password);
        var dev = developers.findFirstByEmailIgnoreCase(email == null ? "" : email.trim())
                .orElseThrow(() -> new IllegalArgumentException("No account uses that email."));
        if (!grants.existsById(dev.getId())) {
            grants.save(new OperatorGrantEntity(dev.getId(), actor));
            log.info("Operator {} granted the operator role to {}", actor, dev.getId());
        }
        cache.remove(dev.getId());
        return Map.of("ok", true, "developerId", dev.getId());
    }

    @Transactional
    public synchronized Map<String, Object> revoke(String actor, String password, String developerId) {
        requireOperator(actor);
        checkPassword(actor, password);
        if (!grants.existsById(developerId)) {
            throw new IllegalArgumentException("That account is not an operator.");
        }
        if (grants.count() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This is the last operator. Grant the role to someone else first.");
        }
        grants.deleteById(developerId);
        cache.remove(developerId);
        log.info("Operator {} removed the operator role from {}", actor, developerId);
        return Map.of("ok", true);
    }

    private void requireOperator(String actor) {
        if (!isOperator(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an operator can do that.");
        }
    }

    private void checkPassword(String actor, String password) {
        String key = "operator-password:" + actor;
        throttle(key);
        DeveloperAuthEntity a = auth.findById(actor).orElse(null);
        if (a == null || password == null || !passwords.matches(password, a.getPasswordHash())) {
            throw new IllegalArgumentException("That password is not right."); // 400, as above
        }
        limiter.reset(key);
    }

    /** Five tries, then one a minute: enough for typos, useless for guessing. */
    private void throttle(String key) {
        RateLimiter.Decision d = limiter.take(key, 5, 1);
        if (!d.allowed()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts. Try again in " + d.retryAfterSeconds() + "s.");
        }
    }

    private static String normalise(String code) {
        return code.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
