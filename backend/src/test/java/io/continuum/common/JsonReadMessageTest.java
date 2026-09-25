package io.continuum.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A failed read becomes a workflow's failure reason, so it must read as one. */
class JsonReadMessageTest {

    record Input(String customerId) {
    }

    @Test
    void wrongTypedInputIsDescribedWithoutJavaNames() {
        Json json = new Json(new ObjectMapper());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> json.read("\"str\"", Input.class));
        assertEquals("The input could not be read: expected a JSON object, got string ('str')", e.getMessage());
        assertFalse(e.getMessage().contains("io.continuum"));
    }
}
