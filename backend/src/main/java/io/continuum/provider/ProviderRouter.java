package io.continuum.provider;

import io.continuum.config.LlmProperties;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.routing.ProviderMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes an LLM request through the configured failover chain.
 *
 * Within a single activity attempt it tries each available provider in order; if
 * one is unavailable or throws (timeout, outage, chaos), it moves to the next.
 * The result records which provider actually served the request. Because the
 * whole call happens inside an activity, the workflow above it stays oblivious
 * to provider failures — exactly the "AI is just another unreliable dependency"
 * philosophy.
 */
@Component
public class ProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);

    private final Map<String, LlmProvider> providers = new LinkedHashMap<>();
    private final LlmProperties props;
    // ObjectProvider avoids any startup ordering/cycle concerns; metrics are purely additive.
    private final ObjectProvider<ProviderMetrics> metrics;

    public ProviderRouter(List<LlmProvider> providerList, LlmProperties props,
                          ObjectProvider<ProviderMetrics> metrics) {
        for (LlmProvider p : providerList) {
            providers.put(p.name(), p);
        }
        this.props = props;
        this.metrics = metrics;
    }

    /** Provider names in failover order that are currently configured/available. */
    public List<String> availableChain() {
        List<String> chain = new ArrayList<>();
        for (String name : props.getFailoverOrder()) {
            LlmProvider p = providers.get(name);
            if (p != null && p.isAvailable()) {
                chain.add(name);
            }
        }
        return chain;
    }

    /** V1 behavior: try providers in the static configured failover order. */
    public LlmResponse complete(LlmRequest request) {
        return complete(request, availableChain());
    }

    /**
     * Try the given ordered provider chain, failing over on error. Identical
     * failover semantics to V1; the model router (Extension 2) supplies a chain
     * here, but the default path passes {@link #availableChain()} so V1 behavior
     * is unchanged. Real latency/token/cost/success stats are recorded per call.
     */
    public LlmResponse complete(LlmRequest request, List<String> chain) {
        return complete(request, chain, null);
    }

    /**
     * Try the given chain using per-provider API key overrides (e.g. a developer's
     * own keys supplied via the gateway). A provider is attempted if an override
     * key is present for it OR it is platform-configured. Passing {@code null}
     * keys reproduces the platform-key behavior exactly.
     */
    public LlmResponse complete(LlmRequest request, List<String> chain, Map<String, String> apiKeysByProvider) {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalStateException("No LLM providers available");
        }
        RuntimeException last = null;
        for (String name : chain) {
            LlmProvider provider = providers.get(name);
            String overrideKey = apiKeysByProvider == null ? null : apiKeysByProvider.get(name);
            if (provider == null || (overrideKey == null && !provider.isAvailable())) {
                continue;
            }
            long start = System.nanoTime();
            try {
                LlmResponse response = provider.complete(request, overrideKey);
                long ms = (System.nanoTime() - start) / 1_000_000;
                double cost = provider.estimateCost(response.model(), response.promptTokens(), response.completionTokens());
                record(m -> m.recordSuccess(name, ms, response.promptTokens(), response.completionTokens(), cost));
                if (last != null) {
                    log.warn("Failover succeeded: served by '{}' after earlier provider(s) failed", name);
                }
                return response;
            } catch (Exception e) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                record(m -> m.recordFailure(name, ms));
                log.warn("Provider '{}' failed: {} — trying next in chain", name, e.getMessage());
                last = new RuntimeException("Provider '" + name + "' failed: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("All providers in failover chain failed", last);
    }

    private void record(java.util.function.Consumer<ProviderMetrics> action) {
        ProviderMetrics m = metrics.getIfAvailable();
        if (m != null) {
            try {
                action.accept(m);
            } catch (Exception ignored) {
                // Metrics must never break a real LLM call.
            }
        }
    }

    /** Provider names that are configured/available (any subset of the registry). */
    public boolean isAvailable(String name) {
        LlmProvider p = providers.get(name);
        return p != null && p.isAvailable();
    }

    public double estimateCost(String providerName, String model, int promptTokens, int completionTokens) {
        LlmProvider p = providers.get(providerName);
        return p == null ? 0.0 : p.estimateCost(model, promptTokens, completionTokens);
    }
}
