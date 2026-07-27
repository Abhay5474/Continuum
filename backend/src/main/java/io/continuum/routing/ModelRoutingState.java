package io.continuum.routing;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime switch for the AI-aware model router.
 *
 * Defaults to <b>disabled</b>, so out of the box LLM activities use the exact V1
 * static failover order — existing behavior is unchanged. Enabling it (via the
 * routing API) makes {@link io.continuum.activities.LlmActivity} select a
 * provider chain per task before execution.
 */
@Component
public class ModelRoutingState {

    /**
     * How a provider order is decided.
     *
     * <p>This exists because "routing enabled" was not a meaningful statement:
     * the gateway ignored the switch entirely and always ran the heuristic
     * scorer, while the contextual bandit observed every outcome and was never
     * consulted. Naming the three strategies makes the choice explicit, and
     * makes {@code LEARNED} a thing you can actually turn on.
     */
    public enum Strategy {
        /** Static availability order — the original V1 behaviour. */
        STATIC,
        /** Cost/latency/quality scorer over aggregate provider statistics. */
        HEURISTIC,
        /** Contextual bandit: Thompson sampling over per-context arm posteriors. */
        LEARNED
    }

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicReference<Strategy> strategy = new AtomicReference<>(Strategy.HEURISTIC);
    private final AtomicReference<RoutingMode> mode = new AtomicReference<>(RoutingMode.BALANCED);
    private final AtomicReference<RoutingPolicy> customPolicy = new AtomicReference<>(null);

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    /** The strategy in force, or {@link Strategy#STATIC} while routing is off. */
    public Strategy getStrategy() {
        return enabled.get() ? strategy.get() : Strategy.STATIC;
    }

    /** The configured strategy, regardless of whether routing is currently on. */
    public Strategy getConfiguredStrategy() {
        return strategy.get();
    }

    public void setStrategy(Strategy s) {
        strategy.set(s == null ? Strategy.HEURISTIC : s);
    }

    public RoutingMode getMode() {
        return mode.get();
    }

    public void setMode(RoutingMode m) {
        mode.set(m);
    }

    public void setCustomPolicy(RoutingPolicy policy) {
        customPolicy.set(policy);
        mode.set(RoutingMode.CUSTOM);
    }

    public RoutingPolicy currentPolicy() {
        if (mode.get() == RoutingMode.CUSTOM && customPolicy.get() != null) {
            return customPolicy.get();
        }
        return RoutingPolicy.of(mode.get());
    }
}
