package io.continuum.autopilot.model;

import java.util.List;

/**
 * The small amount of setup a developer provides to seed Autopilot. Everything
 * else is learned from telemetry. No application code is ever uploaded.
 */
public record DeveloperProfile(
        String applicationName,
        String goal,
        double maxCostPerRequest,
        long maxLatencyMs,
        List<String> allowedProviders,
        List<String> preferredModelClasses,
        AutopilotMode mode) {

    public static DeveloperProfile beginnerDefault() {
        return new DeveloperProfile("My AI App", "reliable, cost-aware responses",
                0.02, 5000, List.of("gemini", "groq", "mock"), List.of(), AutopilotMode.BALANCED);
    }
}
