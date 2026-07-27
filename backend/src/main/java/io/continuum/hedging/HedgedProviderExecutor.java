package io.continuum.hedging;

import io.continuum.hedging.ResponseArbitrator.Attempt;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Tail-latency hedging executor.
 *
 * Launches the primary provider; if it doesn't answer within the policy
 * threshold, it launches a parallel hedge against the next provider (subject to
 * {@link CostBudgetGuard} and {@code maxHedges}) and takes the first successful
 * response, cancelling the rest. Failures trigger immediate failover to the next
 * provider. This trades a bounded amount of extra cost for a large reduction in
 * p99 latency.
 *
 * Deterministically unit-testable via the injected {@link ProviderCaller} and
 * {@link ExecutorService}.
 */
public class HedgedProviderExecutor {

    private final ProviderCaller caller;
    private final ExecutorService pool;
    private final ResponseArbitrator arbitrator;
    private final BudgetCheck budgetCheck;

    /** Budget hook; production passes {@link CostBudgetGuard}, tests pass a lambda. */
    public interface BudgetCheck {
        boolean canAfford(List<String> launched, String candidate, LlmRequest request, Double budget);
    }

    public HedgedProviderExecutor(ProviderCaller caller, ExecutorService pool,
                                  ResponseArbitrator arbitrator, BudgetCheck budgetCheck) {
        this.caller = caller;
        this.pool = pool;
        this.arbitrator = arbitrator;
        this.budgetCheck = budgetCheck;
    }

    public HedgedResult execute(LlmRequest request, List<String> chain, HedgingPolicy policy) {
        return execute(request, chain, policy, null);
    }

    /**
     * Hedged execution with an optional adaptive {@link HedgeGovernor}. When the
     * governor is {@code null} this is the exact original fixed-threshold race;
     * when present, the hedge fires at the live p95 and is gated by the rate cap
     * (V8 — <em>The Tail at Scale</em>). Either way the winner cancels its
     * siblings (tied requests).
     */
    public HedgedResult execute(LlmRequest request, List<String> chain, HedgingPolicy policy,
                                HedgeGovernor governor) {
        return execute(request, chain, policy, governor, this.caller);
    }

    /**
     * As above, but calling through a supplied {@link ProviderCaller}.
     *
     * <p>The gateway needs this: a developer's own provider credentials are
     * resolved per request, so the caller cannot be a singleton built at startup
     * the way the workflow path's is.
     */
    public HedgedResult execute(LlmRequest request, List<String> chain, HedgingPolicy policy,
                                HedgeGovernor governor, ProviderCaller caller) {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalStateException("No providers to hedge over");
        }
        long start = System.nanoTime();
        long thresholdMs = governor == null ? policy.thresholdMs() : governor.effectiveThresholdMs(policy);
        CompletionService<Attempt> ecs = new ExecutorCompletionService<>(pool);
        List<Future<Attempt>> inflight = new ArrayList<>();
        List<String> attempted = new ArrayList<>();

        int nextIdx = 0;
        nextIdx = launch(ecs, inflight, attempted, chain, nextIdx, request, caller);
        Exception lastError = null;

        try {
            while (!inflight.isEmpty()) {
                // Wait up to the (adaptive) hedge threshold for the current leader(s).
                Future<Attempt> done = ecs.poll(thresholdMs, TimeUnit.MILLISECONDS);
                if (done == null) {
                    // Slow: hedge if allowed (rate cap + bounded parallelism + budget).
                    boolean canHedge = inflight.size() <= policy.maxHedges()
                            && nextIdx < chain.size()
                            && (governor == null || governor.allowHedge(policy))
                            && budgetCheck.canAfford(attempted, chain.get(nextIdx), request, policy.perRequestBudgetUsd());
                    if (canHedge) {
                        nextIdx = launch(ecs, inflight, attempted, chain, nextIdx, request, caller);
                    }
                    continue;
                }
                inflight.remove(done);
                Attempt attempt = done.get();
                if (arbitrator.accept(attempt)) {
                    cancel(inflight); // tied requests: winner cancels the losers
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    boolean hedged = attempted.size() > 1;
                    if (governor != null) {
                        governor.recordCompletion(ms, hedged);
                    }
                    return new HedgedResult(attempt.response(), attempt.provider(),
                            hedged, attempted.size(), ms, attempted);
                }
                // Failure -> failover to the next provider immediately (not counted as a hedge).
                lastError = attempt.error();
                if (nextIdx < chain.size()) {
                    nextIdx = launch(ecs, inflight, attempted, chain, nextIdx, request, caller);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel(inflight);
            throw new RuntimeException("Hedged execution interrupted", e);
        } catch (ExecutionException e) {
            lastError = e;
        }
        cancel(inflight);
        throw new RuntimeException("All hedged providers failed", lastError);
    }

    private int launch(CompletionService<Attempt> ecs, List<Future<Attempt>> inflight, List<String> attempted,
                       List<String> chain, int idx, LlmRequest request, ProviderCaller caller) {
        String provider = chain.get(idx);
        attempted.add(provider);
        inflight.add(ecs.submit(() -> {
            long s = System.nanoTime();
            try {
                LlmResponse r = caller.call(provider, request);
                return Attempt.success(provider, r, (System.nanoTime() - s) / 1_000_000);
            } catch (Exception e) {
                return Attempt.failure(provider, e, (System.nanoTime() - s) / 1_000_000);
            }
        }));
        return idx + 1;
    }

    private void cancel(List<Future<Attempt>> futures) {
        for (Future<Attempt> f : futures) {
            f.cancel(true);
        }
    }
}
