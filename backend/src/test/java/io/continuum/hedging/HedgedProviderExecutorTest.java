package io.continuum.hedging;

import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class HedgedProviderExecutorTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ResponseArbitrator arbitrator = new ResponseArbitrator();
    private final HedgedProviderExecutor.BudgetCheck unlimited = (launched, candidate, req, budget) -> true;

    private final LlmRequest request = LlmRequest.of(List.of(Message.user("hi")));

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private LlmResponse resp(String provider) {
        return new LlmResponse("ok-" + provider, List.of(), 10, 5, provider, "m", "stop");
    }

    @Test
    void fastPrimaryWins_noHedge() {
        Map<String, Long> latency = Map.of("fast", 10L, "slow", 5000L);
        ProviderCaller caller = (p, r) -> {
            Thread.sleep(latency.get(p));
            return resp(p);
        };
        var exec = new HedgedProviderExecutor(caller, pool, arbitrator, unlimited);
        var result = exec.execute(request, List.of("fast", "slow"), new HedgingPolicy(200, 1, null));

        assertEquals("fast", result.winningProvider());
        assertFalse(result.hedged(), "primary answered before threshold; no hedge");
        assertEquals(1, result.requestsLaunched());
    }

    @Test
    void slowPrimaryTriggersHedge_fasterHedgeWins() {
        Map<String, Long> latency = Map.of("slow", 5000L, "quick", 20L);
        ProviderCaller caller = (p, r) -> {
            Thread.sleep(latency.get(p));
            return resp(p);
        };
        var exec = new HedgedProviderExecutor(caller, pool, arbitrator, unlimited);
        var result = exec.execute(request, List.of("slow", "quick"), new HedgingPolicy(100, 1, null));

        assertEquals("quick", result.winningProvider(), "hedge should win the race");
        assertTrue(result.hedged());
        assertEquals(2, result.requestsLaunched());
        assertTrue(result.elapsedMs() < 2000, "should not wait for the slow primary");
    }

    @Test
    void failingPrimaryFailsOverToNext() {
        ProviderCaller caller = (p, r) -> {
            if (p.equals("broken")) {
                throw new RuntimeException("provider down");
            }
            return resp(p);
        };
        var exec = new HedgedProviderExecutor(caller, pool, arbitrator, unlimited);
        var result = exec.execute(request, List.of("broken", "healthy"), new HedgingPolicy(1000, 1, null));

        assertEquals("healthy", result.winningProvider());
    }

    @Test
    void budgetGuardPreventsHedge() {
        Map<String, Long> latency = Map.of("slow", 400L, "expensive", 20L);
        ProviderCaller caller = (p, r) -> {
            Thread.sleep(latency.get(p));
            return resp(p);
        };
        HedgedProviderExecutor.BudgetCheck noHedge = (launched, candidate, req, budget) -> false;
        var exec = new HedgedProviderExecutor(caller, pool, arbitrator, noHedge);
        var result = exec.execute(request, List.of("slow", "expensive"), new HedgingPolicy(50, 1, 0.0));

        assertEquals("slow", result.winningProvider(), "budget blocked the hedge, so primary must be awaited");
        assertEquals(1, result.requestsLaunched());
    }
}
