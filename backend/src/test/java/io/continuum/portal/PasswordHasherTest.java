package io.continuum.portal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashesAndVerifies() {
        String stored = hasher.hash("hunter2pass");
        assertFalse(stored.contains("hunter2pass"), "stored hash must not contain the password");
        assertTrue(hasher.matches("hunter2pass", stored));
        assertFalse(hasher.matches("wrong", stored));
    }

    @Test
    void usesPerPasswordSalt() {
        assertNotEquals(hasher.hash("same"), hasher.hash("same"),
                "random salt => different stored hashes for the same password");
    }

    @Test
    void malformedHashDoesNotThrow() {
        assertFalse(hasher.matches("x", "not-a-valid-hash"));
    }
}
