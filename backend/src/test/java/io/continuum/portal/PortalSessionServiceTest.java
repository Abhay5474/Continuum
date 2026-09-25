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

    /**
     * The master key committed in application.yml is public. A deployment that
     * never overrides it must not accept sessions signed with it — otherwise
     * anyone who has read the repository can mint an OPERATOR session.
     */
    @Test
    void publishedMasterKeysNeverSign() {
        String forged = new PortalSessionService("dev-persistent-master-key-change-in-prod")
                .issue("operator", PortalSessionService.Role.OPERATOR);
        for (String published : PortalSessionService.PUBLIC_KEYS) {
            PortalSessionService deployed = new PortalSessionService("", published);
            assertTrue(deployed.verify(forged).isEmpty(), "signed with published key '" + published + "'");
            String own = deployed.issue("dev_1", PortalSessionService.Role.DEVELOPER);
            assertTrue(deployed.verify(own).isPresent(), "its own sessions still work");
        }
    }

    @Test
    void sessionKeyWinsAndPrivateMasterStillWorks() {
        String viaMaster = new PortalSessionService("a-private-master").issue("d", PortalSessionService.Role.DEVELOPER);
        assertTrue(new PortalSessionService("", "a-private-master").verify(viaMaster).isPresent(),
                "deployments that set a private master key keep their sessions");
        String viaSession = new PortalSessionService("sess-key").issue("d", PortalSessionService.Role.DEVELOPER);
        assertTrue(new PortalSessionService("sess-key", "a-private-master").verify(viaSession).isPresent());
        assertTrue(new PortalSessionService("sess-key", "a-private-master").verify(viaMaster).isEmpty());
    }
}
