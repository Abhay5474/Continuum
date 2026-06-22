package io.continuum.vault;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Authenticated symmetric encryption for provider secrets (AES-256-GCM).
 *
 * GCM gives confidentiality AND integrity (tamper detection), so a corrupted or
 * wrong-key ciphertext fails closed rather than returning garbage. Each
 * encryption uses a fresh random 96-bit IV; the output is base64 of
 * {@code IV || ciphertext||tag}. Pure and deterministic-by-key, so it is fully
 * unit-testable.
 */
public class AesGcmCipher {

    private static final int IV_BYTES = 12;       // 96-bit nonce recommended for GCM
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /** @param key32 a 256-bit (32-byte) key. */
    public AesGcmCipher(byte[] key32) {
        if (key32 == null || key32.length != 32) {
            throw new IllegalArgumentException("AES-256-GCM requires a 32-byte key");
        }
        this.key = new SecretKeySpec(key32, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] in = Base64.getDecoder().decode(encoded);
            byte[] iv = Arrays.copyOfRange(in, 0, IV_BYTES);
            byte[] ct = Arrays.copyOfRange(in, IV_BYTES, in.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed (wrong key or tampered ciphertext)", e);
        }
    }

    /** Derive a 256-bit key from an arbitrary passphrase via SHA-256. */
    public static byte[] deriveKey(String passphrase) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(passphrase.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
