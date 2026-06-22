package io.continuum.portal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Issues and verifies stateless portal session tokens (HMAC-SHA256 signed).
 *
 * Token format: {@code base64url(payload).base64url(hmac)} where payload is
 * {@code role:subject:expiryEpochSeconds}. The signing key is derived from
 * {@code CONTINUUM_MASTER_KEY}; tokens are tamper-evident and self-expiring, so
 * no server-side session store is needed.
 */
@Service
public class PortalSessionService {

    public enum Role { DEVELOPER, OPERATOR }

    private static final long DEFAULT_TTL_SECONDS = 12 * 3600;
    private final byte[] signingKey;

    public PortalSessionService(@Value("${CONTINUUM_MASTER_KEY:continuum-dev-secret}") String master) {
        this.signingKey = ("portal-session|" + master).getBytes(StandardCharsets.UTF_8);
    }

    public String issue(String subject, Role role) {
        long expiry = Instant.now().getEpochSecond() + DEFAULT_TTL_SECONDS;
        String payload = role.name() + ":" + subject + ":" + expiry;
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
        String[] f = payload.split(":", 3);
        if (f.length != 3) {
            return Optional.empty();
        }
        long expiry = Long.parseLong(f[2]);
        if (Instant.now().getEpochSecond() > expiry) {
            return Optional.empty(); // expired
        }
        return Optional.of(new Session(f[1], Role.valueOf(f[0]), expiry));
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

    public record Session(String subject, Role role, long expiryEpoch) {
    }
}
