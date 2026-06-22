package io.continuum.developer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates and verifies Continuum API keys (cnt_live_...).
 *
 * The plaintext key is returned to the developer exactly once. We persist only
 * a SHA-256 hash (peppered with the master key when available) plus a short
 * non-secret prefix for O(1) lookup. Verification is constant-time.
 */
@Component
public class ApiKeyHasher {

    private static final String PREFIX = "cnt_live_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String pepper;

    public ApiKeyHasher(@Value("${CONTINUUM_MASTER_KEY:}") String pepper) {
        this.pepper = pepper == null ? "" : pepper;
    }

    public record GeneratedKey(String plaintext, String prefix, String hash) {
    }

    public GeneratedKey generate() {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String plaintext = PREFIX + secret;
        // Non-secret lookup prefix: scheme + first 6 chars of the secret.
        String prefix = PREFIX + secret.substring(0, 6);
        return new GeneratedKey(plaintext, prefix, hash(plaintext));
    }

    public String prefixOf(String plaintext) {
        if (plaintext == null || !plaintext.startsWith(PREFIX) || plaintext.length() < PREFIX.length() + 6) {
            return null;
        }
        return plaintext.substring(0, PREFIX.length() + 6);
    }

    public String hash(String plaintext) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(pepper.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean matches(String plaintext, String expectedHash) {
        return MessageDigest.isEqual(
                hash(plaintext).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
