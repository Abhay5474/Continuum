package io.continuum.hedging;

import io.continuum.provider.model.LlmRequest;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime owner of tail-latency hedging: holds the on/off switch and policy
 * (default OFF, so V1 is unchanged), owns the thread pool, and runs hedged
 * executions through {@link HedgedProviderExecutor}.
 */
@Service
public class HedgingService {

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    // Default to the paper-faithful adaptive policy (p95 trigger, 5% cap).
    private final AtomicReference<HedgingPolicy> policy = new AtomicReference<>(HedgingPolicy.adaptiveDefaults());
    private final AdaptiveHedgeGovernor governor = new AdaptiveHedgeGovernor();

    private final ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "hedge-worker");
        t.setDaemon(true);
        return t;
    });
    private final HedgedProviderExecutor executor;

    public HedgingService(ProviderCaller caller, ResponseArbitrator arbitrator, CostBudgetGuard budgetGuard) {
        this.executor = new HedgedProviderExecutor(caller, pool, arbitrator, budgetGuard::canAfford);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    public HedgingPolicy getPolicy() {
        return policy.get();
    }

    public void setPolicy(HedgingPolicy p) {
        policy.set(p);
    }

    public HedgedResult execute(LlmRequest request, List<String> chain) {
        return executor.execute(request, chain, policy.get(), governor);
    }

    /** Live adaptive-hedging telemetry for the before/after research metric. */
    public java.util.Map<String, Object> metrics() {
        HedgingPolicy p = policy.get();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("adaptive", p.adaptive());
        out.put("effectiveThresholdMs", governor.effectiveThresholdMs(p));
        out.put("configuredThresholdMs", p.thresholdMs());
        out.put("hedgeRateCap", p.hedgeRateCap());
        out.put("observedHedgeRate", governor.lifetimeHedgeRate());
        out.put("currentWindowHedgeRate", governor.currentHedgeRate());
        out.put("latencyP50Ms", governor.p50());
        out.put("latencyP95Ms", governor.p95());
        out.put("latencyP99Ms", governor.p99());
        out.put("totalRequests", governor.totalRequests());
        out.put("totalHedged", governor.totalHedged());
        return out;
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
