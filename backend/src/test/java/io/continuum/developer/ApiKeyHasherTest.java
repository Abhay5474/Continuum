package io.continuum.developer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyHasherTest {

    private final ApiKeyHasher hasher = new ApiKeyHasher("test-pepper");

    @Test
    void generatesPrefixedKeyAndVerifies() {
        ApiKeyHasher.GeneratedKey g = hasher.generate();
        assertTrue(g.plaintext().startsWith("cnt_live_"));
        assertTrue(g.prefix().startsWith("cnt_live_"));
        assertNotEquals(g.plaintext(), g.hash(), "hash must not equal plaintext");
        assertTrue(hasher.matches(g.plaintext(), g.hash()));
    }

    @Test
    void wrongKeyDoesNotMatch() {
        ApiKeyHasher.GeneratedKey g = hasher.generate();
        assertFalse(hasher.matches("cnt_live_wrongwrongwrong", g.hash()));
    }

    @Test
    void prefixExtraction() {
        ApiKeyHasher.GeneratedKey g = hasher.generate();
        assertEquals(g.prefix(), hasher.prefixOf(g.plaintext()));
        assertNull(hasher.prefixOf("not-a-key"));
    }

    @Test
    void pepperAffectsHash() {
        ApiKeyHasher other = new ApiKeyHasher("different-pepper");
        ApiKeyHasher.GeneratedKey g = hasher.generate();
        assertNotEquals(hasher.hash(g.plaintext()), other.hash(g.plaintext()));
    }
}
