package io.continuum.gateway;

import io.continuum.registry.ModelCapabilities;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelFallbackPolicyTest {

    private final ModelFallbackPolicy policy = new ModelFallbackPolicy();

    private ModelFallbackPolicy.ModelOption opt(String model, String tier, double cost, boolean vision) {
        return new ModelFallbackPolicy.ModelOption("groq", model,
                new ModelCapabilities(128000, vision, true, true, cost, cost * 2, tier), 0, 1.0);
    }

    @Test
    void simpleTaskPrefersCheaperModel() {
        var chain = policy.buildChain(List.of(
                opt("strong", "versatile", 0.00005, false),
                opt("cheap", "instant", 0.00001, false)), 0.1, null, false);
        assertEquals("cheap", chain.get(0).model(), "low complexity should route to the cheaper model");
    }

    @Test
    void complexTaskPrefersStrongerModel() {
        var chain = policy.buildChain(List.of(
                opt("strong", "versatile", 0.00005, false),
                opt("cheap", "instant", 0.00001, false)), 0.9, null, false);
        assertEquals("strong", chain.get(0).model(), "high complexity should route to the stronger model");
    }

    @Test
    void visionRequirementFiltersIncompatibleModels() {
        var chain = policy.buildChain(List.of(
                opt("text-only", "versatile", 0.00005, false),
                opt("vision-model", "flash", 0.00006, true)), 0.5, null, true);
        assertEquals(1, chain.size());
        assertEquals("vision-model", chain.get(0).model());
    }

    @Test
    void explicitlyRequestedModelIsPinnedFirst() {
        var chain = policy.buildChain(List.of(
                opt("strong", "versatile", 0.00005, false),
                opt("cheap", "instant", 0.00001, false)), 0.9, "cheap", false);
        assertEquals("cheap", chain.get(0).model(), "explicitly requested model must be tried first");
        assertEquals(2, chain.size(), "other models remain as fallbacks");
    }
}
