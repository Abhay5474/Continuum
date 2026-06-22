package io.continuum.portal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortalSessionServiceTest {

    private final PortalSessionService sessions = new PortalSessionService("unit-test-master");

    @Test
    void issuesAndVerifiesRoundTrip() {
        String token = sessions.issue("dev_123", PortalSessionService.Role.DEVELOPER);
        var s = sessions.verify(token);
        assertTrue(s.isPresent());
        assertEquals("dev_123", s.get().subject());
        assertEquals(PortalSessionService.Role.DEVELOPER, s.get().role());
    }

    @Test
    void rejectsTamperedToken() {
        String token = sessions.issue("dev_123", PortalSessionService.Role.DEVELOPER);
        assertTrue(sessions.verify(token + "x").isEmpty());
        // flip the payload but keep the old signature
        String[] parts = token.split("\\.", 2);
        assertTrue(sessions.verify("AAAA." + parts[1]).isEmpty());
    }

    @Test
    void rejectsTokenSignedWithDifferentKey() {
        String token = new PortalSessionService("other-master").issue("dev_1", PortalSessionService.Role.OPERATOR);
        assertTrue(sessions.verify(token).isEmpty(), "token from a different signing key must not verify");
    }

    @Test
    void garbageIsRejected() {
        assertTrue(sessions.verify(null).isEmpty());
        assertTrue(sessions.verify("not-a-token").isEmpty());
    }
}
