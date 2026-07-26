package io.continuum.portal;

import io.continuum.persistence.entity.TeamInviteEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An invite token is a key to somebody's account, so the interesting cases are
 * all the ways it should stop working.
 */
class InviteLifecycleTest {

    private static TeamInviteEntity invite(Instant expiry) {
        return new TeamInviteEntity("acct-1", "mate@example.com", "tok", expiry);
    }

    @Test
    @DisplayName("a fresh invite is usable")
    void freshInviteIsUsable() {
        assertThat(invite(Instant.now().plusSeconds(3600)).isUsable(Instant.now())).isTrue();
    }

    @Test
    @DisplayName("an expired invite is not usable")
    void expiredInviteIsRefused() {
        assertThat(invite(Instant.now().minusSeconds(1)).isUsable(Instant.now())).isFalse();
    }

    @Test
    @DisplayName("a revoked invite is not usable, even before it expires")
    void revokedInviteIsRefused() {
        TeamInviteEntity i = invite(Instant.now().plusSeconds(3600));
        i.setRevoked(true);
        assertThat(i.isUsable(Instant.now())).isFalse();
    }

    @Test
    @DisplayName("an accepted invite cannot be used a second time")
    void acceptedInviteIsSingleUse() {
        TeamInviteEntity i = invite(Instant.now().plusSeconds(3600));

        i.accept("dev-member");

        assertThat(i.isUsable(Instant.now())).isFalse();
        assertThat(i.isAccepted()).isTrue();
        assertThat(i.getAcceptedBy()).isEqualTo("dev-member");
        assertThat(i.getAcceptedAt()).isNotNull();
    }
}
