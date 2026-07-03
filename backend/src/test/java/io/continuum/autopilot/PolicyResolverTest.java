package io.continuum.autopilot;

import io.continuum.autopilot.model.AutopilotMode;
import io.continuum.autopilot.model.PolicyBundle;
import io.continuum.persistence.entity.AutopilotConfigEntity;
import io.continuum.persistence.repository.AutopilotConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The non-negotiable compatibility guarantee: when Autopilot is OFF (or has no
 * active bundle) the resolver returns EMPTY, so the gateway runs its exact
 * pre-Autopilot path.
 */
class PolicyResolverTest {

    private final AutopilotConfigRepository configRepo = mock(AutopilotConfigRepository.class);
    private final PolicyBundleService bundles = mock(PolicyBundleService.class);
    private final PolicyResolver resolver = new PolicyResolver(configRepo, bundles);

    @Test
    void emptyWhenNoConfig() {
        when(configRepo.findById("dev")).thenReturn(Optional.empty());
        assertTrue(resolver.resolve("dev").isEmpty());
    }

    @Test
    void emptyWhenDisabled() {
        AutopilotConfigEntity config = new AutopilotConfigEntity("dev");
        config.setEnabled(false);
        config.setActiveBundleId(5L);
        when(configRepo.findById("dev")).thenReturn(Optional.of(config));
        assertTrue(resolver.resolve("dev").isEmpty(), "disabled Autopilot must not affect routing");
    }

    @Test
    void emptyWhenEnabledButNoActiveBundle() {
        AutopilotConfigEntity config = new AutopilotConfigEntity("dev");
        config.setEnabled(true);
        when(configRepo.findById("dev")).thenReturn(Optional.of(config));
        assertTrue(resolver.resolve("dev").isEmpty());
    }

    @Test
    void resolvesActiveBundleWhenEnabled() {
        AutopilotConfigEntity config = new AutopilotConfigEntity("dev");
        config.setEnabled(true);
        config.setActiveBundleId(9L);
        when(configRepo.findById("dev")).thenReturn(Optional.of(config));
        when(bundles.bundle(9L)).thenReturn(Optional.of(
                PolicyBundle.defaultFor(AutopilotMode.BALANCED, List.of("gemini"), 0.02, 5000)));
        var resolved = resolver.resolve("dev");
        assertTrue(resolved.isPresent());
        assertFalse(resolved.get().canary());
        assertEquals(9L, resolved.get().bundleId());
    }
}
