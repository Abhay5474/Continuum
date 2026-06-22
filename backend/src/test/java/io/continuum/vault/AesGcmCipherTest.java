package io.continuum.vault;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class AesGcmCipherTest {

    private byte[] key() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    void roundTripsAndNeverStoresPlaintext() {
        AesGcmCipher cipher = new AesGcmCipher(key());
        String secret = "AIza-super-secret-provider-key";
        String encrypted = cipher.encrypt(secret);

        assertNotEquals(secret, encrypted, "ciphertext must differ from plaintext");
        assertFalse(encrypted.contains(secret), "ciphertext must not contain the plaintext");
        assertEquals(secret, cipher.decrypt(encrypted), "must decrypt back to original");
    }

    @Test
    void usesFreshIvSoCiphertextIsNonDeterministic() {
        AesGcmCipher cipher = new AesGcmCipher(key());
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"),
                "random IV per encryption => different ciphertext each time");
    }

    @Test
    void wrongKeyFailsClosed() {
        AesGcmCipher a = new AesGcmCipher(key());
        AesGcmCipher b = new AesGcmCipher(key());
        String ct = a.encrypt("secret");
        assertThrows(IllegalStateException.class, () -> b.decrypt(ct),
                "decrypting with the wrong key must fail, not return garbage");
    }

    @Test
    void tamperedCiphertextIsRejected() {
        AesGcmCipher cipher = new AesGcmCipher(key());
        String ct = cipher.encrypt("secret");
        String tampered = ct.substring(0, ct.length() - 2) + (ct.endsWith("A") ? "B" : "A") + "=";
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered),
                "GCM auth tag must detect tampering");
    }
}
