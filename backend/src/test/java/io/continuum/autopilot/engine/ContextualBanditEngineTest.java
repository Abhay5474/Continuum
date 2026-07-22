package io.continuum.autopilot.engine;

import io.continuum.autopilot.engine.ContextualBanditEngine.Context;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The V8 contextual + non-stationary bandit. Proves the two properties a
 * context-free stationary bandit (the original V4 arm) cannot express.
 */
class ContextualBanditEngineTest {

    private ContextualBanditEngine engine() {
        return new ContextualBanditEngine(0.97, new Random(11));
    }

    @Test
    void contextComplexityBucketsAreBoundedCorrectly() {
        assertEquals(Context.SIMPLE, Context.ofComplexity(0.1));
        assertEquals(Context.MODERATE, Context.ofComplexity(0.5));
        assertEquals(Context.COMPLEX, Context.ofComplexity(0.9));
    }

    @Test
    void differentProvidersWinInDifferentContexts() {
        ContextualBanditEngine b = engine();
        // "cheap" is reliable on SIMPLE traffic; "strong" is reliable on COMPLEX.
        for (int i = 0; i < 200; i++) {
            b.observe(Context.SIMPLE, "cheap", true, 200, 0.0001);
            b.observe(Context.SIMPLE, "strong", i % 2 == 0, 900, 0.002);
            b.observe(Context.COMPLEX, "cheap", i % 3 == 0, 200, 0.0001);
            b.observe(Context.COMPLEX, "strong", true, 900, 0.002);
        }
        var simple = b.rank(Context.SIMPLE, List.of("cheap", "strong"), 1.0, 0, 0);
        var complex = b.rank(Context.COMPLEX, List.of("cheap", "strong"), 1.0, 0, 0);

        assertEquals("cheap", simple.get(0).provider(),
                "on simple traffic the reliable-cheap provider must lead");
        assertEquals("strong", complex.get(0).provider(),
                "on complex traffic the reliable-strong provider must lead — a per-context win a global arm cannot express");
    }

    @Test
    void nonStationaryPosteriorTracksARegimeChange() {
        ContextualBanditEngine b = engine();
        // Phase 1: "flip" is excellent.
        for (int i = 0; i < 300; i++) {
            b.observe(Context.MODERATE, "flip", true, 300, 0.001);
            b.observe(Context.MODERATE, "steady", i % 2 == 0, 300, 0.001);
        }
        assertEquals("flip", b.rank(Context.MODERATE, List.of("flip", "steady"), 1.0, 0, 0).get(0).provider());

        // Phase 2: "flip" silently breaks; "steady" now clearly better.
        for (int i = 0; i < 300; i++) {
            b.observe(Context.MODERATE, "flip", false, 300, 0.001);
            b.observe(Context.MODERATE, "steady", true, 300, 0.001);
        }
        var after = b.rank(Context.MODERATE, List.of("flip", "steady"), 1.0, 0, 0);
        assertEquals("steady", after.get(0).provider(),
                "discounting must let the bandit abandon a provider that recently degraded");
    }

    @Test
    void stationaryGammaOneDoesNotForgetAsFast() {
        // With gamma = 1 (no discount), old evidence sticks — demonstrating the
        // knob that recovers the original V4 stationary behaviour.
        ContextualBanditEngine stationary = new ContextualBanditEngine(1.0, new Random(3));
        for (int i = 0; i < 500; i++) {
            stationary.observe(Context.SIMPLE, "a", true, 100, 0.001);
        }
        for (int i = 0; i < 30; i++) {
            stationary.observe(Context.SIMPLE, "a", false, 100, 0.001);
        }
        // 500 old successes still dominate 30 recent failures under gamma=1.
        var ranked = stationary.rank(Context.SIMPLE, List.of("a"), 1.0, 0, 0);
        assertTrue(ranked.get(0).meanSuccess() > 0.9,
                "gamma=1 keeps all history — the stationary baseline, got " + ranked.get(0).meanSuccess());
    }

    @Test
    void snapshotExposesPerContextPosteriors() {
        ContextualBanditEngine b = engine();
        b.observe(0.1, "cheap", true, 200, 0.0001);   // SIMPLE
        b.observe(0.9, "strong", true, 900, 0.002);   // COMPLEX
        var snap = b.snapshot();
        assertTrue((boolean) snap.get("contextual"));
        assertTrue((boolean) snap.get("nonStationary"));
        @SuppressWarnings("unchecked")
        var byContext = (java.util.Map<String, Object>) snap.get("byContext");
        assertTrue(byContext.containsKey("SIMPLE"));
        assertTrue(byContext.containsKey("COMPLEX"));
    }
}
