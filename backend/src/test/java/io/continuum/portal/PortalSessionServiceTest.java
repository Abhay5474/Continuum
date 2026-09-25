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

    /**
     * A session ends early when the account moves its cut-off forward — the
     * password changed, or "sign out everywhere else" — or when the account is
     * gone. A session issued at the cut-off, the one handed back to the device
     * that asked, survives it.
     */
    @Test
    void revokedSessionsAreRefused() {
        var repo = org.mockito.Mockito.mock(io.continuum.persistence.repository.DeveloperAuthRepository.class);
        var row = new io.continuum.persistence.entity.DeveloperAuthEntity("dev_1", "hash");
        org.mockito.Mockito.when(repo.findById("dev_1")).thenReturn(java.util.Optional.of(row));
        org.mockito.Mockito.when(repo.findById("gone")).thenReturn(java.util.Optional.empty());
        var members = org.mockito.Mockito.mock(io.continuum.persistence.repository.AccountMembershipRepository.class);
        var revocations = new SessionRevocations(repo, members);
        PortalSessionService s = new PortalSessionService("k");
        s.setRevocations(revocations);

        String before = s.issue("dev_1", PortalSessionService.Role.DEVELOPER);
        assertTrue(s.verify(before).isPresent());

        java.time.Instant cutoff = revocations.revokeAll("dev_1");
        assertTrue(s.verify(before).isEmpty(), "issued before the cut-off");
        assertTrue(s.verify(s.issue("dev_1", PortalSessionService.Role.DEVELOPER, cutoff)).isPresent(),
                "the replacement issued at the cut-off");

        // A second revocation straight after the first ends the first one's
        // replacement, even inside the same second.
        String replacement = s.issue("dev_1", PortalSessionService.Role.DEVELOPER, cutoff);
        java.time.Instant second = revocations.revokeAll("dev_1");
        assertTrue(second.isAfter(cutoff));
        assertTrue(s.verify(replacement).isEmpty(), "replacement from the earlier revocation");

        assertTrue(s.verify(s.issue("gone", PortalSessionService.Role.DEVELOPER)).isEmpty(), "deleted account");
        assertTrue(s.verify(s.issue("operator", PortalSessionService.Role.OPERATOR)).isPresent(),
                "operators are not tied to a developer row");
    }

    /**
     * A team member's session reaches the account that invited them but belongs
     * to the member: it names them, it ends when they are removed from the team,
     * and ending the owner's sessions leaves it alone.
     */
    @Test
    void memberSessionsBelongToTheMember() {
        var repo = org.mockito.Mockito.mock(io.continuum.persistence.repository.DeveloperAuthRepository.class);
        var members = org.mockito.Mockito.mock(io.continuum.persistence.repository.AccountMembershipRepository.class);
        var owner = new io.continuum.persistence.entity.DeveloperAuthEntity("owner", "h");
        var member = new io.continuum.persistence.entity.DeveloperAuthEntity("member", "h");
        org.mockito.Mockito.when(repo.findById("owner")).thenReturn(java.util.Optional.of(owner));
        org.mockito.Mockito.when(repo.findById("member")).thenReturn(java.util.Optional.of(member));
        var link = new io.continuum.persistence.entity.AccountMembershipEntity("member", "owner", "m@x.test");
        org.mockito.Mockito.when(members.findById("member")).thenReturn(java.util.Optional.of(link));
        var revocations = new SessionRevocations(repo, members);
        PortalSessionService s = new PortalSessionService("k");
        s.setRevocations(revocations);

        String token = s.issueFor("owner", "member");
        var session = s.verify(token).orElseThrow();
        assertEquals("owner", session.subject());
        assertEquals("member", session.actor());
        assertFalse(session.isOwner());

        revocations.revokeAll("owner");
        assertTrue(s.verify(token).isPresent(), "the owner signing out elsewhere does not end a member's session");

        org.mockito.Mockito.when(members.findById("member")).thenReturn(java.util.Optional.empty());
        revocations.forget("member");
        assertTrue(s.verify(token).isEmpty(), "removed from the team: no longer reaches the account");
    }

    /** Sessions issued before the actor field existed still verify, as their own actor. */
    @Test
    void threePartTokensStillVerify() {
        PortalSessionService s = new PortalSessionService("k");
        String token = s.issue("dev_9", PortalSessionService.Role.DEVELOPER);
        assertEquals(3, new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[0])).split(":").length);
        var session = s.verify(token).orElseThrow();
        assertEquals("dev_9", session.actor());
        assertTrue(session.isOwner());
    }
}
