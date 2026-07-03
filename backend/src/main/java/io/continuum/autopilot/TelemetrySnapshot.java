package io.continuum.autopilot;

import java.util.Map;

/**
 * A point-in-time view of a developer's runtime behaviour, aggregated from real
 * gateway telemetry. Feeds the decision engine, drift detection and canary
 * comparison. Everything here is measured, not simulated.
 */
public record TelemetrySnapshot(
        long totalRequests,
        double successRate,
        double avgLatencyMs,
        double p95LatencyMs,
        double avgCostUsd,
        long failuresPrevented,
        double platformReplayPassRate,       // global semantic-replay quality signal
        Map<String, Arm> providerArms) {

    /** Bandit arm statistics for one provider. */
    public record Arm(long successes, long failures, double avgLatencyMs, double avgCostUsd) {
        public long total() {
            return successes + failures;
        }
    }
}
