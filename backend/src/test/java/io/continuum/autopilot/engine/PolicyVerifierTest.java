package io.continuum.autopilot.engine;

import io.continuum.autopilot.model.AutopilotMode;
import io.continuum.autopilot.model.DeveloperProfile;
import io.continuum.autopilot.model.PolicyBundle;
import io.continuum.persistence.entity.ModelEntity;
import io.continuum.registry.ModelRegistryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PolicyVerifierTest {

    private final ModelRegistryService registry = mock(ModelRegistryService.class);
    private final PolicyVerifier verifier = new PolicyVerifier(registry);

    private DeveloperProfile profile() {
        return new DeveloperProfile("app", "goal", 0.02, 5000, List.of("gemini", "groq", "mock"), List.of(), AutopilotMode.BALANCED);
    }

    private ModelEntity anyActive() {
        return mock(ModelEntity.class);
    }

    @Test
    void passesAWellFormedBundle() {
        when(registry.activeFor(anyString())).thenReturn(List.of(anyActive()));
        PolicyBundle b = PolicyBundle.defaultFor(AutopilotMode.BALANCED, List.of("gemini", "groq"), 0.02, 5000);
        var r = verifier.verify(b, profile());
        assertTrue(r.passed(), "failures: " + r.failures());
    }

    @Test
    void failsWhenProviderHasNoActiveModel() {
        when(registry.activeFor("gemini")).thenReturn(List.of()); // no active model
        when(registry.activeFor("groq")).thenReturn(List.of(anyActive()));
        PolicyBundle b = PolicyBundle.defaultFor(AutopilotMode.BALANCED, List.of("gemini", "groq"), 0.02, 5000);
        var r = verifier.verify(b, profile());
        assertFalse(r.passed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("gemini")));
    }

    @Test
    void failsWhenProviderNotInAllowList() {
        when(registry.activeFor(anyString())).thenReturn(List.of(anyActive()));
        PolicyBundle b = PolicyBundle.defaultFor(AutopilotMode.BALANCED, List.of("openai"), 0.02, 5000);
        var r = verifier.verify(b, profile()); // profile allows gemini/groq/mock only
        assertFalse(r.passed());
    }
}
