package io.continuum.gateway;

import io.continuum.gateway.health.ProviderHealthTracker;
import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.entity.ModelEntity;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import io.continuum.admission.AdmissionService;
import io.continuum.admission.CostAdmissionService;
import io.continuum.admission.CostAwareLimiter;
import io.continuum.admission.Criticality;
import io.continuum.scheduling.DeadlineScheduler;
import io.continuum.scheduling.SchedulerService;
import io.continuum.provider.ProviderRouter;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
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

import static io.continuum.gateway.GatewaySupport.*;

/**
 * Tail-latency hedging: races the top providers and keeps the first good
 * answer. Returns null when the race produces nothing usable, and the gateway
 * falls back to the ordinary sequential chain.
 */
@org.springframework.stereotype.Component
public class HedgeStage {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HedgeStage.class);

    private final ProviderHealthTracker health;
    private final ProviderRouter router;
    private final io.continuum.godmode.GodModeService godMode;
    private final io.continuum.observability.GatewayMetrics metrics;
    private final io.continuum.firewall.PromptFirewallService firewall;
    private final io.continuum.cache.SemanticCacheService semanticCache;
    private final io.continuum.routing.RoutingStrategyService routingStrategy;
    private final io.continuum.hedging.HedgingService hedging;
    private final RequestLog requestLog;
    private final AnswerBookkeeping bookkeeping;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AnswerReview review;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ResponseChaos chaos;

    public HedgeStage(ProviderHealthTracker health, ProviderRouter router, io.continuum.godmode.GodModeService godMode, io.continuum.observability.GatewayMetrics metrics, io.continuum.firewall.PromptFirewallService firewall, io.continuum.cache.SemanticCacheService semanticCache, io.continuum.routing.RoutingStrategyService routingStrategy, io.continuum.hedging.HedgingService hedging, RequestLog requestLog, AnswerBookkeeping bookkeeping) {
        this.health = health;
        this.router = router;
        this.godMode = godMode;
        this.metrics = metrics;
        this.firewall = firewall;
        this.semanticCache = semanticCache;
        this.routingStrategy = routingStrategy;
        this.hedging = hedging;
        this.requestLog = requestLog;
        this.bookkeeping = bookkeeping;
    }
    /**
     * Races the top providers in the chain and returns the first good answer.
     *
     * <p>Returns {@code null} rather than throwing when the race produces
     * nothing usable, so the caller falls back to the ordinary sequential chain.
     * Hedging is an optimisation; it must never be the reason a request fails.
     */
    public GatewayDtos.ChatResponse attempt(
            String developerId, GatewayDtos.ChatRequest req, LlmRequest canonical,
            List<ModelFallbackPolicy.ModelCandidate> chain, Map<String, String> devKeys,
            double complexity, RoutingMode mode, long started,
            io.continuum.routing.RoutingStrategyService.Decision routingDecision,
            java.util.Optional<io.continuum.autopilot.PolicyResolver.ResolvedPolicy> autopilot,
            String cacheKey, io.continuum.provenance.ProvenanceService.Recording prov) {

        // One entry per distinct provider, keeping that provider's best model.
        Map<String, ModelFallbackPolicy.ModelCandidate> byProvider = new java.util.LinkedHashMap<>();
        for (ModelFallbackPolicy.ModelCandidate c : chain) {
            byProvider.putIfAbsent(c.provider(), c);
        }
        if (byProvider.size() < 2) {
            return null;
        }
        List<String> providers = new ArrayList<>(byProvider.keySet());

        try {
            io.continuum.hedging.HedgedResult result = hedging.execute(canonical, providers,
                    (provider, request) -> {
                        ModelFallbackPolicy.ModelCandidate cand = byProvider.get(provider);
                        Map<String, String> keys = devKeys.containsKey(provider)
                                ? Map.of(provider, devKeys.get(provider)) : null;
                        // withModel keeps the caller's tools and JSON mode.
                        return router.complete(request.withModel(cand.model()), List.of(provider), keys);
                    });

            LlmResponse resp = result.response();
            if (resp == null) {
                return null;
            }
            String winner = result.winningProvider();
            ModelFallbackPolicy.ModelCandidate cand = byProvider.get(winner);
            long totalMs = (System.nanoTime() - started) / 1_000_000;

            health.recordSuccess(winner, cand == null ? resp.model() : cand.model(), result.elapsedMs());
            int tokens = resp.promptTokens() + resp.completionTokens();
            double cost = router.estimateCost(winner, resp.model(), resp.promptTokens(), resp.completionTokens());
            // A hedge that fired paid for two calls; reporting one would make
            // hedging look free, which is exactly the tradeoff being made.
            double billedCost = cost * Math.max(1, result.requestsLaunched());

            String reason = String.format("hedged across %s — %s answered first in %dms%s",
                    result.attemptedProviders(), winner, result.elapsedMs(),
                    result.hedged() ? " (hedge fired)" : " (no hedge needed)");

            metrics.request(winner, resp.model(), true, totalMs);
            metrics.tokens(winner, resp.promptTokens(), resp.completionTokens());
            metrics.cost(winner, billedCost);
            if (prov != null) {
                prov.add(new io.continuum.provenance.Decision(
                        io.continuum.provenance.Decision.Stage.PROVIDER,
                        winner + "/" + resp.model(),
                        result.hedged() ? "won a hedged race against " + result.attemptedProviders()
                                : "answered before a hedge was needed",
                        providers.stream().filter(pv -> !pv.equals(winner)).toList(), billedCost, totalMs));
                prov.add(new io.continuum.provenance.Decision(
                        io.continuum.provenance.Decision.Stage.OUTPUT,
                        tokens + " tokens", reason, List.of(), billedCost, totalMs));
                prov.commit();
            }
            var savedLog = requestLog.save(new GatewayRequestLogEntity(developerId, req.model(), winner,
                    resp.model(), complexity, reason, totalMs, tokens, billedCost, true, 0)
                    .withTraceId(prov == null ? null : prov.requestId()));
            bookkeeping.labelForAutopilot(autopilot, savedLog.getId(), developerId, true, totalMs, billedCost);
            bookkeeping.recordBandit(complexity, winner, true, totalMs, billedCost);
            routingStrategy.record(developerId, routingDecision, complexity, winner, true, totalMs, billedCost);

            String safeContent = firewall.guardOutbound(developerId, resp.content());
            godMode.observeExchange(developerId, "gateway", lastUserContent(canonical), safeContent);
            GatewayDtos.ChatResponse answered = completed(new GatewayDtos.ChatResponse(safeContent, winner,
                    resp.model(), totalMs, tokens, billedCost, 0, reason), resp);
            // The same checks as every other path. A hedged answer used to skip
            // the quality gate and confidence measurement entirely, so turning
            // hedging on quietly turned both of them off.
            GatewayDtos.ChatResponse reviewed = review == null ? answered
                    : review.withUncertainty(
                            review.withQualityGate(answered, developerId, canonical, winner, resp.model(),
                                    devKeys, complexity),
                            developerId, req, canonical, winner, resp.model(), devKeys, false);
            cacheIfSound(semanticCache, chaos, developerId, cacheKey, req.model(), winner, reviewed, tokens,
                    billedCost);
            return reviewed;
        } catch (Exception e) {
            log.warn("Hedged execution failed for {}; falling back to the sequential chain: {}",
                    developerId, e.getMessage());
            return null;
        }
    }
}
