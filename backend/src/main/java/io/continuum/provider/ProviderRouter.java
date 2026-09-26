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
                if (e instanceof ModelUnavailableException gone) {
                    LlmResponse retried = onModelUnavailable(request, provider, overrideKey, gone);
                    if (retried != null) {
                        return retried;
                    }
                }
            }
        }
        throw new RuntimeException("All providers in failover chain failed", last);
    }

    /**
     * The provider said the model is gone, or has no free quota for this key.
     *
     * <p>Reported to the catalogue only when it was the platform's key: a
     * developer's own key may simply lack access, which says nothing about the
     * model for anyone else. Then one retry on the same provider, on the model
     * the catalogue now resolves to — so a retirement costs one extra call on
     * one request instead of failing every request until someone edits the
     * configuration. Never more than one: a second failure goes on down the chain.
     */
    private LlmResponse onModelUnavailable(LlmRequest request, LlmProvider provider, String overrideKey,
                                           ModelUnavailableException gone) {
        io.continuum.registry.catalog.ModelCatalogService catalogue = catalogue();
        io.continuum.registry.catalog.ModelResolver resolver = resolver();
        if (overrideKey == null && catalogue != null) {
            try {
                catalogue.reportUnavailable(gone.provider(), gone.model(),
                        gone.reason() == ModelUnavailableException.Reason.GONE, gone.getMessage());
            } catch (RuntimeException e) {
                log.debug("Could not report {}/{}: {}", gone.provider(), gone.model(), e.getMessage());
            }
        }
        if (resolver == null || overrideKey != null) {
            return null;
        }
        String next = resolver.defaultFor(provider.name());
        if (next == null || next.equals(gone.model())) {
            return null;
        }
        long start = System.nanoTime();
        try {
            LlmResponse response = provider.complete(request.withModel(next), null);
            long ms = (System.nanoTime() - start) / 1_000_000;
            double cost = provider.estimateCost(response.model(), response.promptTokens(), response.completionTokens());
            record(m -> m.recordSuccess(provider.name(), ms, response.promptTokens(), response.completionTokens(), cost));
            log.warn("'{}' was unavailable on {}; answered by {} instead", gone.model(), provider.name(), next);
            return response;
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            record(m -> m.recordFailure(provider.name(), ms));
            log.warn("Retry on {} with {} also failed: {}", provider.name(), next, e.getMessage());
            return null;
        }
    }

    private io.continuum.registry.catalog.ModelCatalogService catalogue() {
        return catalogueProvider == null ? null : catalogueProvider.getIfAvailable();
    }

    private io.continuum.registry.catalog.ModelResolver resolver() {
        return resolverProvider == null ? null : resolverProvider.getIfAvailable();
    }

    /** Optional: the router works without a catalogue (unit tests, and before it has started). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ObjectProvider<io.continuum.registry.catalog.ModelCatalogService> catalogueProvider;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ObjectProvider<io.continuum.registry.catalog.ModelResolver> resolverProvider;

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
