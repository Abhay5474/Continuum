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
 * Verify-then-escalate: answer on the cheap tier, judge it, and pay for the
 * strong tier only when the judge says so. Returns null when it cannot apply,
 * and the gateway falls through to the ordinary chain.
 */
@org.springframework.stereotype.Component
public class CascadeStage {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CascadeStage.class);

    private final ProviderHealthTracker health;
    private final ProviderRouter router;
    private final io.continuum.godmode.GodModeService godMode;
    private final io.continuum.observability.GatewayMetrics metrics;
    private final io.continuum.firewall.PromptFirewallService firewall;
    private final io.continuum.cache.SemanticCacheService semanticCache;
    private final io.continuum.routing.RoutingStrategyService routingStrategy;
    private final io.continuum.cascade.ResponseCascadeService cascade;
    private final ResponseChaos chaos;
    private final RequestLog requestLog;
    private final AnswerBookkeeping bookkeeping;
    private final AnswerReview review;

    public CascadeStage(ProviderHealthTracker health, ProviderRouter router, io.continuum.godmode.GodModeService godMode, io.continuum.observability.GatewayMetrics metrics, io.continuum.firewall.PromptFirewallService firewall, io.continuum.cache.SemanticCacheService semanticCache, io.continuum.routing.RoutingStrategyService routingStrategy, io.continuum.cascade.ResponseCascadeService cascade, ResponseChaos chaos, RequestLog requestLog, AnswerBookkeeping bookkeeping, AnswerReview review) {
        this.health = health;
        this.router = router;
        this.godMode = godMode;
        this.metrics = metrics;
        this.firewall = firewall;
        this.semanticCache = semanticCache;
        this.routingStrategy = routingStrategy;
        this.cascade = cascade;
        this.chaos = chaos;
        this.requestLog = requestLog;
        this.bookkeeping = bookkeeping;
        this.review = review;
    }
    /**
     * Answers on the cheap tier, judges it, and escalates only if needed.
     *
     * <p>Returns {@code null} when the cascade cannot apply — no meaningful
     * price gap between models, or the cheap call failed — so the caller falls
     * through to the ordinary chain. The cascade is an optimisation and must
     * never be the reason a request fails.
     */
    public GatewayDtos.ChatResponse attempt(
            String developerId, GatewayDtos.ChatRequest req, LlmRequest canonical,
            Map<String, String> devKeys, double complexity, long started,
            io.continuum.routing.RoutingStrategyService.Decision routingDecision,
            java.util.Optional<io.continuum.autopilot.PolicyResolver.ResolvedPolicy> autopilot,
            String cacheKey, io.continuum.provenance.ProvenanceService.Recording prov) {

        List<io.continuum.cascade.ResponseCascadeService.Tier> pair = cascade.cheapAndStrong();
        if (pair.size() < 2) {
            return null;
        }
        var cheap = pair.get(0);
        var strong = pair.get(1);

        // --- tier 0 -------------------------------------------------------
        long cheapStart = System.nanoTime();
        LlmResponse cheapResp;
        try {
            cheapResp = chaos.apply(developerId, router.complete(
                    new LlmRequest(cheap.model(), canonical.messages(), canonical.maxTokens(),
                            canonical.temperature()),
                    List.of(cheap.provider()), keyFor(devKeys, cheap.provider())));
        } catch (Exception e) {
            // The cheap tier is not special; a failure here is an ordinary
            // provider failure and the normal chain handles it.
            log.warn("Cascade tier 0 ({}) failed; falling back to the standard chain: {}",
                    cheap.model(), e.getMessage());
            return null;
        }
        long cheapMs = (System.nanoTime() - cheapStart) / 1_000_000;
        double cheapCost = router.estimateCost(cheap.provider(), cheapResp.model(),
                cheapResp.promptTokens(), cheapResp.completionTokens());
        health.recordSuccess(cheap.provider(), cheap.model(), cheapMs);

        var assessment = cascade.assess(developerId, canonical, cheapResp.content(), complexity);
        // A sampled slice runs both tiers whatever the verdict says, which is
        // the only way to see the escalations the judge did NOT make.
        boolean audit = !assessment.escalate() && cascade.shouldAudit(developerId);
        boolean runStrong = assessment.escalate() || audit;

        LlmResponse strongResp = null;
        double strongCost = 0;
        if (runStrong) {
            try {
                long t = System.nanoTime();
                strongResp = chaos.apply(developerId, router.complete(
                        new LlmRequest(strong.model(), canonical.messages(), canonical.maxTokens(),
                                canonical.temperature()),
                        List.of(strong.provider()), keyFor(devKeys, strong.provider())));
                strongCost = router.estimateCost(strong.provider(), strongResp.model(),
                        strongResp.promptTokens(), strongResp.completionTokens());
                health.recordSuccess(strong.provider(), strong.model(), (System.nanoTime() - t) / 1_000_000);
            } catch (Exception e) {
                // Escalation failing is survivable: the cheap answer exists and
                // is returned, flagged as un-escalated.
                log.warn("Cascade escalation to {} failed; returning the tier-0 answer: {}",
                        strong.model(), e.getMessage());
            }
        }

        // On an audit the cheap answer is what the caller was going to get, so
        // it is what they get — measuring must not change the measurement.
        boolean served = strongResp != null && assessment.escalate();
        LlmResponse chosen = served ? strongResp : cheapResp;
        String chosenProvider = served ? strong.provider() : cheap.provider();
        double billed = cheapCost + strongCost;
        long totalMs = (System.nanoTime() - started) / 1_000_000;

        cascade.record(developerId, assessment, served, audit,
                cheapResp.content(), strongResp == null ? null : strongResp.content(),
                cheap.model(), strongResp == null ? null : strong.model(),
                cheapCost, strongCost, cheapMs, totalMs, complexity);

        int tokens = chosen.promptTokens() + chosen.completionTokens();
        String reason = served
                ? String.format("cascade: %s escalated to %s — %s", cheap.model(), strong.model(),
                        assessment.reason())
                : String.format("cascade: answered by %s — %s%s", cheap.model(), assessment.reason(),
                        audit ? " (audit sample)" : "");

        // Live metrics and the decision trail, as on the ordinary path. The
        // cascade answered on its own and skipped both: its traffic was missing
        // from the gateway metrics, and no provenance trail was ever written.
        metrics.request(cheap.provider(), cheapResp.model(), true, cheapMs);
        metrics.tokens(cheap.provider(), cheapResp.promptTokens(), cheapResp.completionTokens());
        metrics.cost(cheap.provider(), cheapCost);
        if (strongResp != null) {
            metrics.request(strong.provider(), strongResp.model(), true, totalMs - cheapMs);
            metrics.tokens(strong.provider(), strongResp.promptTokens(), strongResp.completionTokens());
            metrics.cost(strong.provider(), strongCost);
        }
        if (prov != null) {
            prov.add(new io.continuum.provenance.Decision(
                    io.continuum.provenance.Decision.Stage.CASCADE,
                    served ? "escalated to " + strong.model() : "kept " + cheap.model(),
                    assessment.reason() + (audit ? " (audit sample: both tiers ran)" : ""),
                    runStrong ? List.of(strong.model()) : List.of(), cheapCost, cheapMs));
            prov.add(new io.continuum.provenance.Decision(
                    io.continuum.provenance.Decision.Stage.PROVIDER,
                    chosenProvider + "/" + chosen.model(),
                    served ? "the stronger tier's answer was served" : "the cheaper tier's answer was good enough",
                    List.of(), billed, totalMs));
            prov.add(new io.continuum.provenance.Decision(
                    io.continuum.provenance.Decision.Stage.OUTPUT,
                    tokens + " tokens", reason, List.of(), billed, totalMs));
            prov.commit();
        }

        var savedLog = requestLog.save(new GatewayRequestLogEntity(developerId, req.model(), chosenProvider,
                chosen.model(), complexity, reason, totalMs, tokens, billed, true, 0)
                .withTraceId(prov == null ? null : prov.requestId()));
        bookkeeping.labelForAutopilot(autopilot, savedLog.getId(), developerId, true, totalMs, billed);
        bookkeeping.recordBandit(complexity, chosenProvider, true, totalMs, billed);
        routingStrategy.record(developerId, routingDecision, complexity, chosenProvider, true, totalMs, billed);

        String safeContent = firewall.guardOutbound(developerId, chosen.content());
        godMode.observeExchange(developerId, "gateway", lastUserContent(canonical), safeContent);
        semanticCache.store(developerId, cacheKey, req.model(), chosenProvider, safeContent, tokens, billed);

        // The judge's ambivalent band is exactly where a second opinion is worth
        // buying, which is what ADAPTIVE mode targets.
        boolean judgeUnsure = assessment.confidence() < Math.min(1.0, assessment.threshold() + 0.15);
        String finalModel = served ? strong.model() : cheap.model();
        return review.withUncertainty(
                review.withQualityGate(
                        completed(new GatewayDtos.ChatResponse(safeContent, chosenProvider, chosen.model(),
                                totalMs, tokens, billed, 0, reason), chosen),
                        developerId, canonical, chosenProvider, finalModel, devKeys, complexity),
                developerId, req, canonical, chosenProvider, finalModel, devKeys, judgeUnsure);
    }
}
