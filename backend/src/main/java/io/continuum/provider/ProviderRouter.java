package io.continuum.provider;

import io.continuum.config.LlmProperties;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public ProviderRouter(List<LlmProvider> providerList, LlmProperties props) {
        for (LlmProvider p : providerList) {
            providers.put(p.name(), p);
        }
        this.props = props;
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

    public LlmResponse complete(LlmRequest request) {
        List<String> chain = availableChain();
        if (chain.isEmpty()) {
            throw new IllegalStateException("No LLM providers available");
        }
        RuntimeException last = null;
        for (String name : chain) {
            LlmProvider provider = providers.get(name);
            try {
                LlmResponse response = provider.complete(request);
                if (last != null) {
                    log.warn("Failover succeeded: served by '{}' after earlier provider(s) failed", name);
                }
                return response;
            } catch (Exception e) {
                log.warn("Provider '{}' failed: {} — trying next in chain", name, e.getMessage());
                last = new RuntimeException("Provider '" + name + "' failed: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("All providers in failover chain failed", last);
    }

    public double estimateCost(String providerName, String model, int promptTokens, int completionTokens) {
        LlmProvider p = providers.get(providerName);
        return p == null ? 0.0 : p.estimateCost(model, promptTokens, completionTokens);
    }
}
