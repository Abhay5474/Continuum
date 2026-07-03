package io.continuum.autopilot.engine;

import io.continuum.autopilot.TelemetrySnapshot;
import io.continuum.autopilot.model.AutopilotMode;
import io.continuum.autopilot.model.DeveloperProfile;
import io.continuum.autopilot.model.PolicyBundle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DecisionEngineTest {

    private final DecisionEngine engine = new DecisionEngine(new Random(7));

    private PolicyBundle base() {
        return PolicyBundle.defaultFor(AutopilotMode.BALANCED, List.of("groq", "gemini"), 0.02, 5000)
                .withProviderOrder(List.of("gemini", "groq"));
    }

    private DeveloperProfile profile(AutopilotMode mode) {
        return new DeveloperProfile("app", "goal", 0.02, 5000, List.of("gemini", "groq"), List.of(), mode);
    }

    @Test
    void ranksTheReliableProviderFirst() {
        // groq: 95/100 success, gemini: 50/100 success -> groq should lead
        var snapshot = new TelemetrySnapshot(200, 0.72, 800, 1200, 0.001, 5, 1.0, Map.of(
                "gemini", new TelemetrySnapshot.Arm(50, 50, 900, 0.001),
                "groq", new TelemetrySnapshot.Arm(95, 5, 700, 0.001)));
        var p = engine.propose(base(), snapshot, profile(AutopilotMode.BALANCED));
        assertEquals("groq", p.candidate().providerOrder().get(0), p.rationale());
        assertTrue(p.changed());
    }

    @Test
    void lowLatencyModePrefersFasterProviderWhenQualitySimilar() {
        var snapshot = new TelemetrySnapshot(200, 0.9, 500, 900, 0.001, 3, 1.0, Map.of(
                "gemini", new TelemetrySnapshot.Arm(90, 10, 2000, 0.001),
                "groq", new TelemetrySnapshot.Arm(90, 10, 300, 0.001)));
        var p = engine.propose(base(), snapshot, profile(AutopilotMode.LOW_LATENCY));
        assertEquals("groq", p.candidate().providerOrder().get(0), "faster provider should lead in LOW_LATENCY");
    }

    @Test
    void hedgeThresholdTightensWhenP95ExceedsBudget() {
        var snapshot = new TelemetrySnapshot(200, 0.9, 4000, 8000 /* p95 > 5000 budget */, 0.001, 3, 1.0, Map.of(
                "gemini", new TelemetrySnapshot.Arm(90, 10, 4000, 0.001),
                "groq", new TelemetrySnapshot.Arm(88, 12, 4200, 0.001)));
        var p = engine.propose(base(), snapshot, profile(AutopilotMode.BALANCED));
        assertTrue(p.candidate().hedgeThresholdMs() <= 3000,
                "hedge should trigger earlier when p95 exceeds the latency budget");
    }

    @Test
    void noChangeWhenAlreadyOptimalAndStable() {
        var snapshot = new TelemetrySnapshot(200, 0.95, 800, 1200, 0.001, 2, 1.0, Map.of(
                "gemini", new TelemetrySnapshot.Arm(96, 4, 800, 0.001),
                "groq", new TelemetrySnapshot.Arm(60, 40, 800, 0.001)));
        // current order already gemini-first, hedge default -> engine may keep it
        var current = base(); // gemini, groq
        var p = engine.propose(current, snapshot, profile(AutopilotMode.HIGH_QUALITY));
        assertEquals("gemini", p.candidate().providerOrder().get(0));
    }
}
