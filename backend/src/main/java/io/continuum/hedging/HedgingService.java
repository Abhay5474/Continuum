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
    private final AtomicReference<HedgingPolicy> policy = new AtomicReference<>(HedgingPolicy.defaults());

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
        return executor.execute(request, chain, policy.get());
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
