package io.continuum.chaos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized fault injection so the system's resilience can be demonstrated on
 * demand. Toggled at runtime via the chaos API. Everything here is a no-op
 * unless explicitly enabled, so production behavior is unaffected.
 */
@Component
public class ChaosMonkey {

    private static final Logger log = LoggerFactory.getLogger(ChaosMonkey.class);

    /** Probability [0,1] that any activity randomly throws before doing work. */
    private volatile double activityFailureRate = 0.0;

    /** Probability [0,1] that an outbox sink delivery throws. */
    private volatile double sinkFailureRate = 0.0;

    /** When true, the primary LLM provider always fails (forces failover). */
    private final AtomicBoolean primaryProviderDown = new AtomicBoolean(false);

    /** Artificial latency (ms) injected into activities. */
    private volatile long activityLatencyMs = 0;

    /**
     * If > 0, the worker is asked to "die" (throw a hard error) after this many
     * activity executions, simulating a crash mid-flight. Counts down.
     */
    private final AtomicInteger crashAfterActivities = new AtomicInteger(0);

    public void maybeFailActivity(String activityType) {
        if (activityLatencyMs > 0) {
            sleep(activityLatencyMs);
        }
        if (crashAfterActivities.get() > 0 && crashAfterActivities.decrementAndGet() == 0) {
            log.warn("CHAOS: simulating worker crash during activity '{}'", activityType);
            throw new SimulatedCrashError("Simulated worker crash during " + activityType);
        }
        if (activityFailureRate > 0 && ThreadLocalRandom.current().nextDouble() < activityFailureRate) {
            log.warn("CHAOS: injecting failure into activity '{}'", activityType);
            throw new RuntimeException("CHAOS: injected activity failure");
        }
    }

    public void maybeFailSink(String destination) throws Exception {
        if (sinkFailureRate > 0 && ThreadLocalRandom.current().nextDouble() < sinkFailureRate) {
            log.warn("CHAOS: injecting failure into sink '{}'", destination);
            throw new Exception("CHAOS: injected sink failure");
        }
    }

    public boolean isPrimaryProviderDown() {
        return primaryProviderDown.get();
    }

    public void setPrimaryProviderDown(boolean down) {
        primaryProviderDown.set(down);
    }

    public void setActivityFailureRate(double rate) {
        this.activityFailureRate = clamp(rate);
    }

    public void setSinkFailureRate(double rate) {
        this.sinkFailureRate = clamp(rate);
    }

    public void setActivityLatencyMs(long ms) {
        this.activityLatencyMs = Math.max(0, ms);
    }

    public void scheduleCrashAfter(int activities) {
        this.crashAfterActivities.set(Math.max(0, activities));
    }

    public ChaosState state() {
        return new ChaosState(activityFailureRate, sinkFailureRate, primaryProviderDown.get(),
                activityLatencyMs, crashAfterActivities.get());
    }

    public void reset() {
        activityFailureRate = 0;
        sinkFailureRate = 0;
        primaryProviderDown.set(false);
        activityLatencyMs = 0;
        crashAfterActivities.set(0);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record ChaosState(double activityFailureRate, double sinkFailureRate, boolean primaryProviderDown,
                             long activityLatencyMs, int crashAfterActivities) {
    }

    /** Marker error so simulated crashes are distinguishable in logs/tests. */
    public static final class SimulatedCrashError extends RuntimeException {
        public SimulatedCrashError(String message) {
            super(message);
        }
    }
}
