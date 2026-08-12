package io.continuum.chaos;

import io.continuum.portal.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fault injection, scoped to the tenant that asked for it.
 *
 * <p>Fault injection used to be a single global switch. On a shared engine that
 * made it a denial-of-service primitive: any signed-up developer could mark the
 * primary provider down and every other tenant's traffic failed over with them.
 * A drill has to be safe to run on a Tuesday afternoon in production, so each
 * developer now gets their own fault profile and only their own requests,
 * activities and deliveries see it.
 *
 * <p>The engine operator keeps a separate global profile for exercising the whole
 * system. Faults compose: a tenant's request is affected if either its own
 * profile or the global one fires.
 *
 * <p>The tenant comes from {@link TenantContext} rather than a parameter, because
 * the call sites are providers, activities and outbox sinks that have no business
 * knowing about tenancy.
 */
@Component
public class ChaosMonkey {

    private static final Logger log = LoggerFactory.getLogger(ChaosMonkey.class);

    /** The operator's engine-wide profile. */
    private final Faults global = new Faults();

    /** Per-developer profiles, created on first use and dropped on reset. */
    private final Map<String, Faults> perTenant = new ConcurrentHashMap<>();

    public void maybeFailActivity(String activityType) {
        Faults tenant = current();

        long latency = Math.max(global.activityLatencyMs, tenant == null ? 0 : tenant.activityLatencyMs);
        if (latency > 0) {
            sleep(latency);
        }
        if (global.consumeCrash() || (tenant != null && tenant.consumeCrash())) {
            log.warn("CHAOS: simulating worker crash during activity '{}'", activityType);
            throw new SimulatedCrashError("Simulated worker crash during " + activityType);
        }
        double rate = Math.max(global.activityFailureRate, tenant == null ? 0 : tenant.activityFailureRate);
        if (rate > 0 && ThreadLocalRandom.current().nextDouble() < rate) {
            log.warn("CHAOS: injecting failure into activity '{}'", activityType);
            throw new RuntimeException("CHAOS: injected activity failure");
        }
    }

    public void maybeFailSink(String destination) throws Exception {
        Faults tenant = current();
        double rate = Math.max(global.sinkFailureRate, tenant == null ? 0 : tenant.sinkFailureRate);
        if (rate > 0 && ThreadLocalRandom.current().nextDouble() < rate) {
            log.warn("CHAOS: injecting failure into sink '{}'", destination);
            throw new Exception("CHAOS: injected sink failure");
        }
    }

    public boolean isPrimaryProviderDown() {
        Faults tenant = current();
        return global.primaryProviderDown.get() || (tenant != null && tenant.primaryProviderDown.get());
    }

    // --- control plane -------------------------------------------------------
    // A null developerId addresses the operator's global profile; anything else
    // addresses that tenant's own profile.

    public void setPrimaryProviderDown(String developerId, boolean down) {
        profile(developerId).primaryProviderDown.set(down);
    }

    /**
     * How often a provider call fails, for this profile.
     *
     * <p>Distinct from {@code primaryProviderDown}, which is all-or-nothing. The
     * failure a reliability layer is actually judged on is intermittent — a
     * provider that fails three calls in ten, not one that is cleanly gone — and
     * an all-or-nothing switch cannot produce it.
     */
    public void setProviderFailureRate(String developerId, double rate) {
        profile(developerId).providerFailureRate = clamp(rate);
    }

    /**
     * Whether this provider call should fail, for whoever this thread serves.
     *
     * <p>Deterministic rather than random: at rate r the nth call fails iff
     * {@code floor((n+1)r) > floor(nr)}, which yields exactly {@code floor(Nr)}
     * failures in N calls, evenly spaced. A benchmark built on a random draw
     * reports a different number every run and cannot be used to argue that
     * anything improved.
     */
    public boolean shouldFailProviderCall() {
        Faults tenant = current();
        Faults f = (tenant != null && tenant.providerFailureRate > 0) ? tenant
                : (global.providerFailureRate > 0 ? global : null);
        if (f == null) {
            return false;
        }
        double rate = f.providerFailureRate;
        long n = f.providerCalls.getAndIncrement();
        return (long) Math.floor((n + 1) * rate) > (long) Math.floor(n * rate);
    }

    public void setActivityFailureRate(String developerId, double rate) {
        profile(developerId).activityFailureRate = clamp(rate);
    }

    public void setSinkFailureRate(String developerId, double rate) {
        profile(developerId).sinkFailureRate = clamp(rate);
    }

    public void setActivityLatencyMs(String developerId, long ms) {
        profile(developerId).activityLatencyMs = Math.max(0, ms);
    }

    public void scheduleCrashAfter(String developerId, int activities) {
        profile(developerId).crashAfterActivities.set(Math.max(0, activities));
    }

    /** The caller's own view: what they have armed, not what anyone else has. */
    public ChaosState state(String developerId) {
        Faults f = profile(developerId);
        return new ChaosState(f.activityFailureRate, f.sinkFailureRate, f.primaryProviderDown.get(),
                f.activityLatencyMs, f.crashAfterActivities.get(), developerId == null ? "engine" : "account",
                f.providerFailureRate);
    }

    public void reset(String developerId) {
        if (developerId == null) {
            global.reset();
        } else {
            perTenant.remove(developerId);
        }
    }

    private Faults profile(String developerId) {
        return developerId == null ? global : perTenant.computeIfAbsent(developerId, k -> new Faults());
    }

    /** The profile of whoever this thread is working for, if anyone. */
    private Faults current() {
        String dev = TenantContext.developerId();
        return dev == null ? null : perTenant.get(dev);
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

    /** One profile's armed faults. */
    private static final class Faults {
        volatile double activityFailureRate = 0.0;
        volatile double sinkFailureRate = 0.0;
        volatile double providerFailureRate = 0.0;
        /** Call counter, so the deterministic schedule above has an index. */
        final java.util.concurrent.atomic.AtomicLong providerCalls = new java.util.concurrent.atomic.AtomicLong();
        final AtomicBoolean primaryProviderDown = new AtomicBoolean(false);
        volatile long activityLatencyMs = 0;
        final AtomicInteger crashAfterActivities = new AtomicInteger(0);

        boolean consumeCrash() {
            return crashAfterActivities.get() > 0 && crashAfterActivities.decrementAndGet() == 0;
        }

        void reset() {
            activityFailureRate = 0;
            sinkFailureRate = 0;
            providerFailureRate = 0;
            providerCalls.set(0);
            primaryProviderDown.set(false);
            activityLatencyMs = 0;
            crashAfterActivities.set(0);
        }
    }

    public record ChaosState(double activityFailureRate, double sinkFailureRate, boolean primaryProviderDown,
                             long activityLatencyMs, int crashAfterActivities, String scope,
                             double providerFailureRate) {
    }

    /** A provider failure that was armed on purpose, distinguishable in a log. */
    public static final class SimulatedProviderFailure extends RuntimeException {
        public SimulatedProviderFailure(String message) {
            super(message);
        }
    }

    /** Marker error so simulated crashes are distinguishable in logs/tests. */
    public static final class SimulatedCrashError extends RuntimeException {
        public SimulatedCrashError(String message) {
            super(message);
        }
    }
}
