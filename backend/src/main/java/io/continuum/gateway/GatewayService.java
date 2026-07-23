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
    // Autopilot integration (additive; empty resolution ⇒ exact pre-Autopilot behaviour).
    private final io.continuum.autopilot.PolicyResolver policyResolver;
    private final io.continuum.persistence.repository.AutopilotRequestLabelRepository autopilotLabels;
    // God Mode memory observation (additive; strict no-op unless the developer opted in).
    private final io.continuum.godmode.GodModeService godMode;
    // V6 Consensus DAG (additive; gate is FALSE by default ⇒ exact legacy path).
    private final io.continuum.dag.ConsensusDagService consensusDag;
    // V7 Context MMU (additive; open() returns null unless opted in ⇒ exact legacy path).
    private final io.continuum.mmu.ContextMMU contextMmu;
    // V8 contextual+non-stationary bandit (additive; record-only, never alters routing).
    private final io.continuum.autopilot.engine.ContextualBanditEngine contextualBandit;
    // V8 prompt compression + firewall (additive; pass-through unless opted in).
    private final io.continuum.compression.PromptCompressionService compression;
    private final io.continuum.firewall.PromptFirewallService firewall;
    // Billing quota enforcement (default FREE plan is generous ⇒ unchanged behaviour).
    private final io.continuum.billing.BillingService billing;

    public GatewayService(RequestNormalizer normalizer, TaskComplexityEstimator complexityEstimator,
                          ProviderSelectionEngine selectionEngine, ModelRegistryService registry,
                          ModelFallbackPolicy fallbackPolicy, ProviderHealthTracker health,
                          CredentialVaultService vault, ProviderRouter router,
                          GatewayRequestLogRepository logRepo,
                          io.continuum.persistence.repository.DeveloperAuthRepository devAuth,
                          io.continuum.autopilot.PolicyResolver policyResolver,
                          io.continuum.persistence.repository.AutopilotRequestLabelRepository autopilotLabels,
                          io.continuum.godmode.GodModeService godMode,
                          io.continuum.dag.ConsensusDagService consensusDag,
                          io.continuum.mmu.ContextMMU contextMmu,
                          io.continuum.autopilot.engine.ContextualBanditEngine contextualBandit,
                          io.continuum.compression.PromptCompressionService compression,
                          io.continuum.firewall.PromptFirewallService firewall,
                          io.continuum.billing.BillingService billing) {
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
        this.policyResolver = policyResolver;
        this.autopilotLabels = autopilotLabels;
        this.godMode = godMode;
        this.consensusDag = consensusDag;
        this.contextMmu = contextMmu;
        this.contextualBandit = contextualBandit;
        this.compression = compression;
        this.firewall = firewall;
        this.billing = billing;
    }

    /** Record a routing outcome into the contextual bandit; never affects the request. */
    private void recordBandit(double complexity, String provider, boolean success, long latencyMs, double cost) {
        try {
            contextualBandit.observe(complexity, provider, success, latencyMs, cost);
        } catch (Exception ignored) {
            // Learning must never break a request.
        }
    }

    public GatewayDtos.ChatResponse chat(String developerId, GatewayDtos.ChatRequest req) {
        // Billing: reject requests once the monthly token quota is exhausted
        // (throws QuotaExceededException → mapped to 402 upstream). The default
        // FREE plan quota is generous, so this is transparent for normal use.
        billing.assertWithinQuota(developerId);
        // V6 Consensus DAG Engine (opt-in, OFF by default): when the developer
        // enabled it in the portal, the request is verified through the DAG and
        // returned in the identical response shape. When the flag is off — or
        // the DAG fails for any reason — everything below is the exact legacy path.
        if (consensusDag.enabledFor(developerId)) {
            try {
                return consensusDag.run(developerId, req);
            } catch (Exception e) {
                log.warn("V6 DAG run failed for {}, falling back to legacy path: {}",
                        developerId, e.getMessage());
            }
        }
        long started = System.nanoTime();
        LlmRequest canonical = normalizer.normalize(req);
        // V8 Prompt Firewall (opt-in, OFF by default): redact PII and block
        // prompt-injection BEFORE anything else touches the prompt. Pass-through
        // when off. A blocked request throws (mapped to a clean 4xx upstream).
        canonical = firewall.guardInbound(developerId, canonical);
        // God Mode Twin Gate: Interpose and augment request with memory if enabled
        canonical = godMode.augmentRequest(developerId, canonical);
        // V7 Context MMU (opt-in, OFF by default): virtualize the context window.
        // open() returns null unless the developer enabled it — null ⇒ the full
        // prompt array below is exactly what it always was.
        io.continuum.mmu.ContextMMU.MmuSession mmuSession = contextMmu.open(developerId, canonical);
        if (mmuSession != null) {
            canonical = mmuSession.request();
        }
        // V8 Prompt Compression (opt-in, OFF by default): shrink context tokens.
        // Pass-through when off; runs after paging so it compresses the final context.
        canonical = compression.maybeCompress(developerId, canonical);
        double complexity = complexityEstimator.estimate(canonical).complexity();
        boolean requireVision = Boolean.TRUE.equals(req.requireVision());
        RoutingMode mode = parseMode(req.routingMode());

        // Autopilot (opt-in): empty unless the developer enabled it AND has an
        // active bundle. When empty, everything below is the exact V3 path.
        java.util.Optional<io.continuum.autopilot.PolicyResolver.ResolvedPolicy> autopilot =
                policyResolver.resolve(developerId);
        if (autopilot.isPresent() && autopilot.get().policy().routingMode() != null
                && (req.routingMode() == null || req.routingMode().isBlank())) {
            mode = parseMode(autopilot.get().policy().routingMode());
        }

        // "Use my provider keys as primary" preference (default true).
        boolean useOwnKeys = devAuth.findById(developerId)
                .map(io.continuum.persistence.entity.DeveloperAuthEntity::isUseOwnKeysPrimary).orElse(true);

        // Provider preference: when the developer opts in, their OWN configured
        // providers come first (their keys, their request); otherwise we route on
        // platform keys. Ordering otherwise uses the measured-stats scorer (reused).
        SelectionResult selection = selectionEngine.select(canonical, RoutingPolicy.of(mode));
        Map<String, Integer> providerRank = buildProviderOrder(developerId, selection, useOwnKeys);
        if (autopilot.isPresent()) {
            providerRank = applyAutopilotOrder(autopilot.get().policy().providerOrder(), providerRank);
        }

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
                if (mmuSession != null) {
                    // V7 page-fault interception: if the model requested a paged
                    // segment, materialize it from L3 and re-dispatch (bounded).
                    final Map<String, String> faultKeys = keys;
                    final String provider = c.provider();
                    final String model = c.model();
                    resp = mmuSession.interceptFaults(resp, r -> router.complete(
                            new LlmRequest(model, r.messages(), r.maxTokens(), r.temperature()),
                            List.of(provider), faultKeys));
                    mmuSession.finish(resp);
                }
                long attemptMs = (System.nanoTime() - attemptStart) / 1_000_000;
                health.recordSuccess(c.provider(), c.model(), attemptMs);

                int tokens = resp.promptTokens() + resp.completionTokens();
                double cost = router.estimateCost(c.provider(), resp.model(),
                        resp.promptTokens(), resp.completionTokens());
                long totalMs = (System.nanoTime() - started) / 1_000_000;
                String reason = routingReason(complexity, mode, c, failovers);

                var savedLog = logRepo.save(new GatewayRequestLogEntity(developerId, req.model(), c.provider(),
                        resp.model(), complexity, reason, totalMs, tokens, cost, true, failovers));
                labelForAutopilot(autopilot, savedLog.getId(), developerId, true, totalMs, cost);
                recordBandit(complexity, c.provider(), true, totalMs, cost);
                // V8 Prompt Firewall (opt-in): scan the outbound response for leaked
                // secrets. Pass-through when off.
                String safeContent = firewall.guardOutbound(developerId, resp.content());
                // God Mode (opt-in): observe the exchange into working memory.
                // No-op (and can never throw) unless the developer enabled it.
                godMode.observeExchange(developerId, "gateway",
                        lastUserContent(canonical), safeContent);

                return new GatewayDtos.ChatResponse(safeContent, c.provider(), resp.model(),
                        totalMs, tokens, cost, failovers, reason);
            } catch (Exception e) {
                long attemptMs = (System.nanoTime() - attemptStart) / 1_000_000;
                health.recordFailure(c.provider(), c.model(), attemptMs, e.getMessage());
                recordBandit(complexity, c.provider(), false, attemptMs, 0);
                failovers++;
                lastError = new RuntimeException(e.getMessage(), e);
                log.warn("Gateway: {} {} failed, falling over: {}", c.provider(), c.model(), e.getMessage());
            }
        }
        long totalMs = (System.nanoTime() - started) / 1_000_000;
        var failLog = logFailure(developerId, req, complexity, mode);
        labelForAutopilot(autopilot, failLog == null ? null : failLog.getId(), developerId, false, totalMs, 0);
        throw new GatewayException("All eligible providers failed for this request"
                + (lastError != null ? ": " + lastError.getMessage() : ""));
    }

    private static String lastUserContent(LlmRequest canonical) {
        for (int i = canonical.messages().size() - 1; i >= 0; i--) {
            var m = canonical.messages().get(i);
            if ("user".equalsIgnoreCase(m.role().name())) {
                return m.content();
            }
        }
        return null;
    }

    /** Reorders providers so the Autopilot policy's preferred order takes precedence. */
    private Map<String, Integer> applyAutopilotOrder(List<String> policyOrder, Map<String, Integer> base) {
        if (policyOrder == null || policyOrder.isEmpty()) {
            return base;
        }
        Map<String, Integer> out = new HashMap<>();
        int rank = 0;
        for (String p : policyOrder) {
            out.put(p, rank++);
        }
        int shift = rank;
        for (var e : base.entrySet()) {
            out.putIfAbsent(e.getKey(), shift + e.getValue());
        }
        return out;
    }

    private void labelForAutopilot(java.util.Optional<io.continuum.autopilot.PolicyResolver.ResolvedPolicy> ap,
                                   Long gatewayRequestId, String developerId, boolean success, long latencyMs, double cost) {
        if (ap.isEmpty()) {
            return;
        }
        try {
            autopilotLabels.save(new io.continuum.persistence.entity.AutopilotRequestLabelEntity(
                    gatewayRequestId, developerId, ap.get().bundleId(), ap.get().canary(), success, latencyMs, cost));
        } catch (Exception ignored) {
            // Labeling must never break a request.
        }
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
                try {
                    vault.decrypt(developerId, c.provider()).ifPresent(secret -> keys.put(c.provider(), secret));
                } catch (Exception e) {
                    log.warn("Gateway: Failed to decrypt credentials for developer {} on provider {} (falling back to platform keys): {}",
                            developerId, c.provider(), e.getMessage());
                }
            }
        }
        return keys;
    }

    private GatewayRequestLogEntity logFailure(String developerId, GatewayDtos.ChatRequest req,
                                               double complexity, RoutingMode mode) {
        try {
            return logRepo.save(new GatewayRequestLogEntity(developerId, req.model(), null, null,
                    complexity, "no provider succeeded (mode " + mode + ")", 0, 0, 0, false, 0));
        } catch (Exception ignored) {
            return null;
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
