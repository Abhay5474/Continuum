package io.continuum.portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

/**
 * Issues and verifies stateless portal session tokens (HMAC-SHA256 signed).
 *
 * Token format: {@code base64url(payload).base64url(hmac)} where payload is
 * {@code role:subject:expiryEpochSeconds}. The signing key is derived from
 * {@code CONTINUUM_SESSION_KEY}, else a private {@code CONTINUUM_MASTER_KEY}; tokens are tamper-evident and self-expiring, so
 * no server-side session store is needed.
 */
@Service
public class PortalSessionService {

    public enum Role { DEVELOPER, OPERATOR }

    private static final long DEFAULT_TTL_SECONDS = 12 * 3600;
    private final byte[] signingKey;

    /**
     * Master-key values that are published in this repository. A session signed
     * with one of them can be forged by anyone who has read the source — an
     * OPERATOR session included, which reads every tenant and reaches the admin
     * API — so they are never used to sign.
     */
    public static final Set<String> PUBLIC_KEYS =
            Set.of("", "continuum-dev-secret", "dev-persistent-master-key-change-in-prod");

    private static final Logger log = LoggerFactory.getLogger(PortalSessionService.class);

    @Autowired
    public PortalSessionService(@Value("${CONTINUUM_SESSION_KEY:}") String sessionKey,
                                @Value("${CONTINUUM_MASTER_KEY:}") String master) {
        this.signingKey = ("portal-session|" + chooseSecret(sessionKey, master)).getBytes(StandardCharsets.UTF_8);
    }

    /** Signs with {@code master} alone — for tests and callers that manage their own secret. */
    public PortalSessionService(String master) {
        this.signingKey = ("portal-session|" + master).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The dedicated session key if one is set; otherwise the master key, unless it
     * is a published default; otherwise a random key for this process. The last
     * case costs sign-ins across a restart, which is the correct trade: the
     * alternative is sessions anyone can mint.
     */
    static String chooseSecret(String sessionKey, String master) {
        if (sessionKey != null && !sessionKey.isBlank()) {
            return sessionKey;
        }
        if (master != null && !PUBLIC_KEYS.contains(master.trim())) {
            return master;
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        log.warn("Neither CONTINUUM_SESSION_KEY nor a private CONTINUUM_MASTER_KEY is set: console sessions are "
                + "signed with a per-process key and will end when the server restarts.");
        return Base64.getEncoder().encodeToString(random);
    }

    /** Ended-early sessions; absent in unit tests that only exercise signing. */
    private SessionRevocations revocations;

    @Autowired(required = false)
    void setRevocations(SessionRevocations revocations) {
        this.revocations = revocations;
    }

    public String issue(String subject, Role role) {
        return issue(subject, role, Instant.now(), subject);
    }

    public String issue(String subject, Role role, Instant issuedAt) {
        return issue(subject, role, issuedAt, subject);
    }

    /** A session in {@code subject}'s account, held by {@code actor} — a team member, or the owner. */
    public String issueFor(String subject, String actor) {
        return issue(subject, Role.DEVELOPER, Instant.now(), actor);
    }

    /**
     * A session counted as issued at {@code issuedAt}. The issue time is not in
     * the token; it is its expiry less the fixed lifetime, which is what lets
     * revocation work without changing the token format.
     */
    /**
     * @param actor the person holding the session. For an account owner this is
     *              the subject; for a team member it is their own id while the
     *              subject is the account they work in. Tokens issued before this
     *              field existed have three parts and read as actor = subject.
     */
    public String issue(String subject, Role role, Instant issuedAt, String actor) {
        long expiry = issuedAt.getEpochSecond() + DEFAULT_TTL_SECONDS;
        String payload = role.name() + ":" + subject + ":" + expiry
                + (actor == null || actor.equals(subject) ? "" : ":" + actor);
        String p = b64(payload.getBytes(StandardCharsets.UTF_8));
        return p + "." + b64(hmac(p));
    }

    public Optional<Session> verify(String token) {
        if (token == null || !token.contains(".")) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }
        byte[] expected = hmac(parts[0]);
        byte[] presented;
        try {
            presented = Base64.getUrlDecoder().decode(parts[1]);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!java.security.MessageDigest.isEqual(expected, presented)) {
            return Optional.empty();
        }
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String[] f = payload.split(":", 4);
        if (f.length < 3) {
            return Optional.empty();
        }
        long expiry = Long.parseLong(f[2]);
        String actor = f.length == 4 ? f[3] : f[1];
        if (Instant.now().getEpochSecond() > expiry) {
            return Optional.empty(); // expired
        }
        Role role = Role.valueOf(f[0]);
        // Keyed by the person, not the account: a member changing their password
        // ends their own sessions, not the owner's and every teammate's.
        if (role == Role.DEVELOPER && revocations != null
                && revocations.revoked(actor, f[1], expiry - DEFAULT_TTL_SECONDS)) {
            return Optional.empty(); // signed out everywhere, password changed, or account deleted
        }
        return Optional.of(new Session(f[1], role, expiry, actor));
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /**
     * @param subject the account whose data the session reaches
     * @param actor   who is holding it — equal to subject except for team members
     */
    public record Session(String subject, Role role, long expiryEpoch, String actor) {

        public Session(String subject, Role role, long expiryEpoch) {
            this(subject, role, expiryEpoch, subject);
        }

        public boolean isOwner() {
            return subject.equals(actor);
        }
    }
}
