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

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicReference<RoutingMode> mode = new AtomicReference<>(RoutingMode.BALANCED);
    private final AtomicReference<RoutingPolicy> customPolicy = new AtomicReference<>(null);

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
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
