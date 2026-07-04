package io.continuum.dag;

import io.continuum.persistence.entity.DeveloperAuthEntity;
import io.continuum.persistence.repository.DeveloperAuthRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * THE V6 compatibility guarantee: the gate is FALSE by default (fresh account,
 * unknown developer, null developer), so the gateway's legacy path runs
 * untouched unless the developer explicitly flips the portal toggle.
 */
class V6OffPathTest {

    private final DeveloperAuthRepository devAuth = mock(DeveloperAuthRepository.class);
    private final ConsensusDagService service = new ConsensusDagService(
            devAuth, null, null, null, null, null, null, null, 1000);

    @Test
    void freshAccountsAreOffByDefault() {
        DeveloperAuthEntity auth = new DeveloperAuthEntity("dev-1", "hash");
        when(devAuth.findById("dev-1")).thenReturn(Optional.of(auth));

        assertFalse(auth.isV6DagEnabled(), "the entity default must be OFF");
        assertFalse(service.enabledFor("dev-1"), "a fresh account never enters the DAG path");
    }

    @Test
    void unknownAndNullDevelopersAreOff() {
        when(devAuth.findById("ghost")).thenReturn(Optional.empty());
        assertFalse(service.enabledFor("ghost"));
        assertFalse(service.enabledFor(null));
    }

    @Test
    void explicitOptInFlipsTheGateAndOptOutRestoresIt() {
        DeveloperAuthEntity auth = new DeveloperAuthEntity("dev-1", "hash");
        when(devAuth.findById("dev-1")).thenReturn(Optional.of(auth));
        when(devAuth.save(auth)).thenReturn(auth);

        service.setEnabled("dev-1", true);
        assertTrue(service.enabledFor("dev-1"));
        service.setEnabled("dev-1", false);
        assertFalse(service.enabledFor("dev-1"), "disable restores the exact legacy gate");
    }
}
