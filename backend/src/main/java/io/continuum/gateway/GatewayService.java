package io.continuum.gateway;

import io.continuum.gateway.health.ProviderHealthTracker;
import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.entity.ModelEntity;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import io.continuum.provider.ProviderRouter;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.registry.ModelRegistryService;
import io.continuum.routing.ProviderSelectionEngine;
import io.continuum.routing.RoutingMode;
import io.continuum.routing.RoutingPolicy;
import io.continuum.routing.SelectionResult;
import io.continuum.routing.TaskComplexityEstimator;
import io.continuum.vault.CredentialVaultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The gateway control plane.
 *
 * For each developer request it: normalizes → estimates complexity → orders
 * providers using the existing measured-stats scorer (Extension 2, reused) →
 * builds a capability-aware (provider, model) fallback chain → resolves the
 * developer's own provider keys from the vault → executes through the existing
 * {@link ProviderRouter} (V1 failover, unchanged) walking the chain until one
 * succeeds. Every attempt updates health; the outcome is logged for the
 * developer dashboard. Provider failures are absorbed here — the developer sees
 * a successful response (or a single secure error), never the failover churn.
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final RequestNormalizer normalizer;
    private final TaskComplexityEstimator complexityEstimator;
    private final ProviderSelectionEngine selectionEngine;
    private final ModelRegistryService registry;
    private final ModelFallbackPolicy fallbackPolicy;
    private final ProviderHealthTracker health;
    private final CredentialVaultService vault;
    private final ProviderRouter router;
    private final GatewayRequestLogRepository logRepo;
    private final io.continuum.persistence.repository.DeveloperAuthRepository devAuth;

    public GatewayService(RequestNormalizer normalizer, TaskComplexityEstimator complexityEstimator,
                          ProviderSelectionEngine selectionEngine, ModelRegistryService registry,
                          ModelFallbackPolicy fallbackPolicy, ProviderHealthTracker health,
                          CredentialVaultService vault, ProviderRouter router,
                          GatewayRequestLogRepository logRepo,
                          io.continuum.persistence.repository.DeveloperAuthRepository devAuth) {
        this.normalizer = normalizer;
        this.complexityEstimator = complexityEstimator;
        this.selectionEngine = selectionEngine;
        this.registry = registry;
        this.fallbackPolicy = fallbackPolicy;
        this.health = health;
        this.vault = vault;
        this.router = router;
        this.logRepo = logRepo;
        this.devAuth = devAuth;
    }

    public GatewayDtos.ChatResponse chat(String developerId, GatewayDtos.ChatRequest req) {
        long started = System.nanoTime();
        LlmRequest canonical = normalizer.normalize(req);
        double complexity = complexityEstimator.estimate(canonical).complexity();
        boolean requireVision = Boolean.TRUE.equals(req.requireVision());
        RoutingMode mode = parseMode(req.routingMode());

        // "Use my provider keys as primary" preference (default true).
        boolean useOwnKeys = devAuth.findById(developerId)
                .map(io.continuum.persistence.entity.DeveloperAuthEntity::isUseOwnKeysPrimary).orElse(true);

        // Provider preference: when the developer opts in, their OWN configured
        // providers come first (their keys, their request); otherwise we route on
        // platform keys. Ordering otherwise uses the measured-stats scorer (reused).
        SelectionResult selection = selectionEngine.select(canonical, RoutingPolicy.of(mode));
        Map<String, Integer> providerRank = buildProviderOrder(developerId, selection, useOwnKeys);

        // Build capability-aware (provider, model) options from ACTIVE registry models.
        List<ModelFallbackPolicy.ModelOption> options = new ArrayList<>();
        for (ModelEntity m : registry.active()) {
            options.add(new ModelFallbackPolicy.ModelOption(
                    m.getProvider(), m.getModelName(), registry.capabilitiesOf(m),
                    providerRank.getOrDefault(m.getProvider(), Integer.MAX_VALUE),
                    health.healthScore(m.getProvider(), m.getModelName())));
        }
        List<ModelFallbackPolicy.ModelCandidate> chain =
                fallbackPolicy.buildChain(options, complexity, canonical.model(), requireVision);

        if (chain.isEmpty()) {
            logFailure(developerId, req, complexity, mode);
            throw new GatewayException("No eligible model is available for this request");
        }

        // Resolve developer-supplied provider keys (decrypted only here, never logged/returned).
        Map<String, String> devKeys = useOwnKeys ? resolveKeys(developerId, chain) : Map.of();

        int failovers = 0;
        RuntimeException lastError = null;
        for (ModelFallbackPolicy.ModelCandidate c : chain) {
            LlmRequest perModel = new LlmRequest(c.model(), canonical.messages(),
                    canonical.maxTokens(), canonical.temperature());
            Map<String, String> keys = devKeys.containsKey(c.provider())
                    ? Map.of(c.provider(), devKeys.get(c.provider())) : null;
            long attemptStart = System.nanoTime();
            try {
                LlmResponse resp = router.complete(perModel, List.of(c.provider()), keys);
                long attemptMs = (System.nanoTime() - attemptStart) / 1_000_000;
                health.recordSuccess(c.provider(), c.model(), attemptMs);

                int tokens = resp.promptTokens() + resp.completionTokens();
                double cost = router.estimateCost(c.provider(), resp.model(),
                        resp.promptTokens(), resp.completionTokens());
                long totalMs = (System.nanoTime() - started) / 1_000_000;
                String reason = routingReason(complexity, mode, c, failovers);

                logRepo.save(new GatewayRequestLogEntity(developerId, req.model(), c.provider(), resp.model(),
                        complexity, reason, totalMs, tokens, cost, true, failovers));

                return new GatewayDtos.ChatResponse(resp.content(), c.provider(), resp.model(),
                        totalMs, tokens, cost, failovers, reason);
            } catch (Exception e) {
                long attemptMs = (System.nanoTime() - attemptStart) / 1_000_000;
                health.recordFailure(c.provider(), c.model(), attemptMs, e.getMessage());
                failovers++;
                lastError = new RuntimeException(e.getMessage(), e);
                log.warn("Gateway: {} {} failed, falling over: {}", c.provider(), c.model(), e.getMessage());
            }
        }
        logFailure(developerId, req, complexity, mode);
        throw new GatewayException("All eligible providers failed for this request"
                + (lastError != null ? ": " + lastError.getMessage() : ""));
    }

    /**
     * Ranks providers: the developer's own configured providers first (ordered by
     * the measured-stats scorer when present), then platform providers from the
     * scorer, then any other registry providers as last resort.
     */
    private Map<String, Integer> buildProviderOrder(String developerId, SelectionResult selection, boolean useOwnKeys) {
        java.util.LinkedHashSet<String> order = new java.util.LinkedHashSet<>();
        if (useOwnKeys) {
            List<String> devProviders = vault.listProviders(developerId).stream()
                    .map(CredentialVaultService.CredentialInfo::provider).toList();
            // Developer-configured providers first, in scorer order when available.
            for (String p : selection.chosenChain()) {
                if (devProviders.contains(p)) {
                    order.add(p);
                }
            }
            order.addAll(devProviders);
        }
        // Then platform-available providers from the scorer, then everything else.
        order.addAll(selection.chosenChain());
        for (ModelEntity m : registry.active()) {
            order.add(m.getProvider());
        }
        Map<String, Integer> rank = new HashMap<>();
        int i = 0;
        for (String p : order) {
            rank.put(p, i++);
        }
        return rank;
    }

    private Map<String, String> resolveKeys(String developerId, List<ModelFallbackPolicy.ModelCandidate> chain) {
        Map<String, String> keys = new HashMap<>();
        for (ModelFallbackPolicy.ModelCandidate c : chain) {
            if (!keys.containsKey(c.provider())) {
                vault.decrypt(developerId, c.provider()).ifPresent(secret -> keys.put(c.provider(), secret));
            }
        }
        return keys;
    }

    private void logFailure(String developerId, GatewayDtos.ChatRequest req, double complexity, RoutingMode mode) {
        try {
            logRepo.save(new GatewayRequestLogEntity(developerId, req.model(), null, null,
                    complexity, "no provider succeeded (mode " + mode + ")", 0, 0, 0, false, 0));
        } catch (Exception ignored) {
        }
    }

    private String routingReason(double complexity, RoutingMode mode, ModelFallbackPolicy.ModelCandidate c, int failovers) {
        String basis = complexity >= 0.5 ? "high complexity → stronger model" : "low complexity → cheaper model";
        return String.format("mode=%s, complexity=%.2f, %s → %s/%s%s",
                mode, complexity, basis, c.provider(), c.model(),
                failovers > 0 ? " (after " + failovers + " failover(s))" : "");
    }

    private RoutingMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return RoutingMode.BALANCED;
        }
        try {
            return RoutingMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RoutingMode.BALANCED;
        }
    }

    /** Thrown when the gateway cannot fulfil a request; mapped to a secure error by the controller. */
    public static class GatewayException extends RuntimeException {
        public GatewayException(String message) {
            super(message);
        }
    }
}
