package io.continuum.gateway;

import io.continuum.gateway.health.ProviderHealthTracker;
import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.entity.ModelEntity;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import io.continuum.admission.AdmissionService;
import io.continuum.admission.Criticality;
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
    private final AdmissionService admission;
    private final io.continuum.quality.AnswerRepairService repairEngine;
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
    // Semantic cache (opt-in, OFF by default): serves a previous answer when the
    // incoming prompt means the same thing. Never on the critical path when off.
    private final io.continuum.cache.SemanticCacheService semanticCache;
    // Routing strategy + hedging. Both existed and neither was reachable from
    // this path: the on/off switch was never read, the bandit was never asked,
    // and hedging lived only in the workflow activity.
    private final io.continuum.routing.RoutingStrategyService routingStrategy;
    private final io.continuum.hedging.HedgingService hedging;
    // Verify-then-escalate. Answers on the cheap tier, judges the answer, and
    // pays for the strong tier only when the judge says the cheap one failed.
    private final io.continuum.cascade.ResponseCascadeService cascade;
    // Semantic entropy over resampled answers — the only signal here that tells
    // a caller whether to trust the answer it just received.
    private final io.continuum.uncertainty.SemanticUncertaintyService uncertainty;
    // AI-level fault injection. Armed per tenant, and — like hedging before this
    // — it previously only reached durable workflows, so a drill against the
    // gateway did nothing at all.
    private final io.continuum.aichaos.AiChaosEngine aiChaos;
    // Checks the finished answer against the request that asked for it.
    private final io.continuum.quality.QualityGate qualityGate;
    private final io.continuum.quality.QualityGateService quality;
    // Trips a model out of rotation when its answers degrade, not when it errors.
    private final io.continuum.drift.SemanticBreakerService breaker;

    public GatewayService(RequestNormalizer normalizer, TaskComplexityEstimator complexityEstimator,
                          ProviderSelectionEngine selectionEngine, ModelRegistryService registry,
                          ModelFallbackPolicy fallbackPolicy, ProviderHealthTracker health,
                          CredentialVaultService vault, ProviderRouter router,
                          AdmissionService admission,
                          io.continuum.quality.AnswerRepairService repairEngine,
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
                          io.continuum.billing.BillingService billing,
                          io.continuum.cache.SemanticCacheService semanticCache,
                          io.continuum.routing.RoutingStrategyService routingStrategy,
                          io.continuum.hedging.HedgingService hedging,
                          io.continuum.cascade.ResponseCascadeService cascade,
                          io.continuum.uncertainty.SemanticUncertaintyService uncertainty,
                          io.continuum.aichaos.AiChaosEngine aiChaos,
                          io.continuum.quality.QualityGate qualityGate,
                          io.continuum.quality.QualityGateService quality,
                          io.continuum.drift.SemanticBreakerService breaker) {
        this.normalizer = normalizer;
        this.complexityEstimator = complexityEstimator;
        this.selectionEngine = selectionEngine;
        this.registry = registry;
        this.fallbackPolicy = fallbackPolicy;
        this.health = health;
        this.vault = vault;
        this.router = router;
        this.admission = admission;
        this.repairEngine = repairEngine;
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
        this.semanticCache = semanticCache;
        this.routingStrategy = routingStrategy;
        this.hedging = hedging;
        this.cascade = cascade;
        this.uncertainty = uncertainty;
        this.aiChaos = aiChaos;
        this.qualityGate = qualityGate;
        this.quality = quality;
        this.breaker = breaker;
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

        // Semantic cache (opt-in, OFF by default): if this question has already
        // been answered, return that answer instead of paying a provider for it
        // again. Placed after the firewall so a cached prompt is already
        // redacted, and before routing/paging/compression — all of which exist
        // to serve the call we are about to skip.
        String cacheKey = lastUserContent(canonical);
        if (semanticCache.enabledFor(developerId)) {
            var hit = semanticCache.lookup(developerId, cacheKey, req.model());
            if (hit.isPresent()) {
                var h = hit.get();
                long cachedMs = (System.nanoTime() - started) / 1_000_000;
                // Logged like any other request, with zero cost, so usage and
                // spend reporting stay truthful about what the cache avoided.
                logRepo.save(new GatewayRequestLogEntity(developerId, req.model(), h.provider(),
                        h.model(), 0, "semantic-cache", cachedMs, 0, 0, true, 0));
                return new GatewayDtos.ChatResponse(h.response(), h.provider(), h.model(),
                        cachedMs, 0, 0, 0,
                        String.format("served from semantic cache (%s match, similarity %.2f)",
                                h.exact() ? "exact" : "near", h.similarity()));
            }
        }

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

        // Which strategy actually orders providers — static, the heuristic
        // scorer, or the contextual bandit. Until now the gateway ran the
        // scorer unconditionally and never consulted the bandit at all, so both
        // the routing switch and every posterior the console drew were inert.
        io.continuum.routing.RoutingStrategyService.Decision routingDecision =
                routingStrategy.decide(selection.chosenChain(), router.availableChain(), complexity);

        Map<String, Integer> providerRank = buildProviderOrder(developerId,
                new SelectionResult(selection.mode(), selection.complexity(), selection.approxPromptTokens(),
                        routingDecision.order(), selection.scores(), routingDecision.explanation()),
                useOwnKeys);
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

        // Divert away from models whose quality has drifted. Filtered rather than
        // excluded from scoring, so if EVERY model is tripped the request still
        // goes somewhere: a degraded answer beats no answer.
        if (breaker.enabledFor(developerId) && chain.size() > 1) {
            List<ModelFallbackPolicy.ModelCandidate> permitted = new ArrayList<>();
            for (ModelFallbackPolicy.ModelCandidate c : chain) {
                if (breaker.allows(developerId, c.provider(), c.model())) {
                    permitted.add(c);
                }
            }
            if (!permitted.isEmpty()) {
                chain = permitted;
            }
        }

        if (chain.isEmpty()) {
            logFailure(developerId, req, complexity, mode);
            throw new GatewayException("No eligible model is available for this request");
        }

        // Resolve developer-supplied provider keys (decrypted only here, never logged/returned).
        Map<String, String> devKeys = useOwnKeys ? resolveKeys(developerId, chain) : Map.of();

        // Verify-then-escalate cascade (per-tenant, opt-in, OFF by default).
        // Runs ahead of hedging and the ordinary chain: when it produces an
        // answer, nothing below needs to. Declines silently when the registry
        // offers no meaningful price difference between models.
        if (cascade.enabledFor(developerId)) {
            GatewayDtos.ChatResponse cascaded = tryCascade(developerId, req, canonical, devKeys,
                    complexity, started, routingDecision, autopilot, cacheKey);
            if (cascaded != null) {
                return cascaded;
            }
        }

        // Tail-latency hedging (engine-wide, opt-in, OFF by default). When a
        // provider is slow past the governor's live p95, a second request goes
        // to the next provider and the first answer back wins. This existed for
        // durable workflows only; the gateway — the path an external
        // application actually uses — never had it.
        if (hedging.isEnabled() && chain.size() > 1) {
            GatewayDtos.ChatResponse hedged = tryHedged(developerId, req, canonical, chain, devKeys,
                    complexity, mode, started, routingDecision, autopilot, cacheKey);
            if (hedged != null) {
                return hedged;
            }
            // A failed race is not a failed request: fall through to the
            // ordinary sequential chain, which is the behaviour without hedging.
        }

        int failovers = 0;
        RuntimeException lastError = null;
        for (ModelFallbackPolicy.ModelCandidate c : chain) {
            LlmRequest perModel = new LlmRequest(c.model(), canonical.messages(),
                    canonical.maxTokens(), canonical.temperature());
            Map<String, String> keys = devKeys.containsKey(c.provider())
                    ? Map.of(c.provider(), devKeys.get(c.provider())) : null;
            long attemptStart = System.nanoTime();
            // Congestion-controlled admission. The slot is held for exactly the
            // provider call: holding it across the quality gate or the cascade
            // would count time the provider is not busy against its capacity.
            AdmissionService.Slot slot = admissionSlot(developerId, c.provider(), req);
            try {
                LlmResponse resp = chaos(developerId, router.complete(perModel, List.of(c.provider()), keys));
                if (slot != null) {
                    slot.success();
                }
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
                // The counterfactual: what the heuristic would have chosen is
                // stored beside what actually ran, so "learning helped" is a
                // number rather than a claim.
                routingStrategy.record(developerId, routingDecision, complexity,
                        c.provider(), true, totalMs, cost);
                // V8 Prompt Firewall (opt-in): scan the outbound response for leaked
                // secrets. Pass-through when off.
                String safeContent = firewall.guardOutbound(developerId, resp.content());
                // God Mode (opt-in): observe the exchange into working memory.
                // No-op (and can never throw) unless the developer enabled it.
                godMode.observeExchange(developerId, "gateway",
                        lastUserContent(canonical), safeContent);

                // Cache the answer for the next equivalent question. No-op when
                // the cache is off, and it can never fail the request.
                semanticCache.store(developerId, cacheKey, req.model(), c.provider(),
                        safeContent, tokens, cost);

                // Gate first, then measure: there is no point measuring the
                // confidence of an answer that is about to be replaced.
                return withUncertainty(
                        withQualityGate(
                                new GatewayDtos.ChatResponse(safeContent, c.provider(), resp.model(),
                                        totalMs, tokens, cost, failovers, reason),
                                developerId, canonical, c.provider(), c.model(), devKeys, complexity),
                        developerId, req, canonical, c.provider(), c.model(), devKeys, false);
            } catch (Exception e) {
                long attemptMs = (System.nanoTime() - attemptStart) / 1_000_000;
                // A provider failure is the signal the gradient cannot see in
                // time — back the limit off rather than waiting for latency to
                // drift.
                if (slot != null) {
                    slot.dropped();
                }
                health.recordFailure(c.provider(), c.model(), attemptMs, e.getMessage());
                recordBandit(complexity, c.provider(), false, attemptMs, 0);
                routingStrategy.record(developerId, routingDecision, complexity,
                        c.provider(), false, attemptMs, 0);
                failovers++;
                lastError = new RuntimeException(e.getMessage(), e);
                log.warn("Gateway: {} {} failed, falling over: {}", c.provider(), c.model(), e.getMessage());
            } finally {
                // Idempotent: success()/dropped() already closed it. This only
                // catches paths that returned or threw without either.
                if (slot != null) {
                    slot.close();
                }
            }
        }
        long totalMs = (System.nanoTime() - started) / 1_000_000;
        var failLog = logFailure(developerId, req, complexity, mode);
        labelForAutopilot(autopilot, failLog == null ? null : failLog.getId(), developerId, false, totalMs, 0);
        throw new GatewayException("All eligible providers failed for this request"
                + (lastError != null ? ": " + lastError.getMessage() : ""));
    }

    /**
     * Runs the quality gate over a finished answer, repairing it if enforcing.
     *
     * <p>Returns the response unchanged on anything unexpected, and on a repair
     * that runs out of budget. The answer already exists; discarding it because
     * a check failed to complete would be the wrong trade every time.
     */
    private GatewayDtos.ChatResponse withQualityGate(
            GatewayDtos.ChatResponse response, String developerId, LlmRequest canonical,
            String provider, String model, Map<String, String> devKeys, double complexity) {

        boolean gateOn = quality.activeFor(developerId);
        boolean breakerOn = breaker.enabledFor(developerId);
        if (!gateOn && !breakerOn) {
            return response;
        }
        try {
            var cfg = quality.settingsFor(developerId);
            var verdict = qualityGate.check(canonical, response.response(), complexity, cfg.getThreshold());

            // The breaker's input. Scoring is free — no model call — so it works
            // whether or not the gate itself is enabled, and the breaker does not
            // inherit the gate's mode.
            breaker.observe(developerId, provider, model, verdict.score(),
                    verdict.defects().isEmpty() ? null : verdict.summary());

            if (!gateOn) {
                // Breaker only: observe and get out of the way.
                return response;
            }

            boolean enforce = cfg.getMode() == io.continuum.persistence.entity.QualityGateSettingEntity.Mode.ENFORCE;
            // BLOCK is a refusal. Asking again is how you get the same refusal
            // twice and pay for both, so it is never repaired.
            boolean shouldRepair = enforce
                    && verdict.action() == io.continuum.quality.QualityGate.Action.REPAIR
                    && cfg.getMaxRepairs() > 0;

            if (!shouldRepair) {
                // MONITOR records the intent without acting on it; that gap is
                // the evidence for whether enforcing would help.
                quality.record(developerId, cfg, verdict, "NONE", model, response.response(),
                        null, null, 0, 0);
                return annotate(response, verdict, false);
            }

            // The targeted repair engine, when the developer has turned it on:
            // one defect kind per attempt, re-checked after each, and any
            // attempt that scores lower than what it replaced is discarded.
            if (cfg.isRepairEngineEnabled()) {
                return withRepairEngine(response, developerId, canonical, provider, model,
                        devKeys, complexity, cfg, verdict);
            }

            long start = System.nanoTime();
            List<Message> repairMessages = new ArrayList<>(canonical.messages());
            repairMessages.add(Message.assistant(response.response()));
            repairMessages.add(Message.user(verdict.repairInstruction()));

            LlmResponse repaired = chaos(developerId, router.complete(
                    new LlmRequest(model, repairMessages, canonical.maxTokens(), canonical.temperature()),
                    List.of(provider), keyFor(devKeys, provider)));
            long repairMs = (System.nanoTime() - start) / 1_000_000;

            if (repairMs > cfg.getBudgetMs()) {
                // Over budget: the repair may well be better, but latency is part
                // of the contract too. Record the miss rather than hiding it.
                quality.record(developerId, cfg, verdict, "BUDGET_EXCEEDED", model,
                        response.response(), null, null, 0, repairMs);
                return annotate(response, verdict, false);
            }

            double repairCost = router.estimateCost(provider, repaired.model(),
                    repaired.promptTokens(), repaired.completionTokens());
            String safe = firewall.guardOutbound(developerId, repaired.content());
            var after = qualityGate.check(canonical, safe, complexity, cfg.getThreshold());

            // Only keep the repair if it actually helped. A "correction" that
            // scores worse is a regression the gate caused itself.
            boolean better = after.score() > verdict.score();
            quality.record(developerId, cfg, verdict, better ? "REPAIR" : "REPAIR_REJECTED", model,
                    response.response(), safe, after, repairCost, repairMs);

            if (!better) {
                return annotate(response, verdict, false);
            }
            return annotate(new GatewayDtos.ChatResponse(safe, response.provider(), response.model(),
                    response.latency() + repairMs, response.tokens(), response.cost() + repairCost,
                    response.failovers(),
                    response.routingReason() + String.format(" · repaired (%.2f → %.2f): %s",
                            verdict.score(), after.score(), verdict.summary()),
                    response.confidence(), response.lowConfidence(), response.agreementClusters()),
                    after, true);
        } catch (Exception e) {
            log.warn("Quality gate failed for {}; returning the answer unchecked: {}",
                    developerId, e.getMessage());
            return response;
        }
    }


    /**
     * The Answer Repair Engine path.
     *
     * <p>Differs from the single-shot repair in the guard that makes it safe:
     * every attempt is re-scored by the same external gate and discarded if it
     * did not help. Huang et al. (ICLR 2024) showed a model asked to reconsider
     * will degrade correct work; the point here is that nothing relies on the
     * model's own judgement of its answer.
     */
    private GatewayDtos.ChatResponse withRepairEngine(
            GatewayDtos.ChatResponse response, String developerId, LlmRequest canonical,
            String provider, String model, Map<String, String> devKeys, double complexity,
            io.continuum.persistence.entity.QualityGateSettingEntity cfg,
            io.continuum.quality.QualityGate.Verdict verdict) {

        var result = repairEngine.repair(developerId, model, canonical, response.response(),
                verdict, complexity, cfg.getThreshold(), cfg.getMaxRepairs(), cfg.getBudgetMs(),
                messages -> {
                    LlmResponse r = chaos(developerId, router.complete(
                            new LlmRequest(model, messages, canonical.maxTokens(),
                                    canonical.temperature()),
                            List.of(provider), keyFor(devKeys, provider)));
                    String safe = firewall.guardOutbound(developerId, r.content());
                    double c = router.estimateCost(provider, r.model(),
                            r.promptTokens(), r.completionTokens());
                    return new io.continuum.quality.AnswerRepairService.Regenerate.Attempt(safe, c);
                });

        quality.record(developerId, cfg, verdict, result.improved() ? "REPAIR" : "REPAIR_REJECTED",
                model, response.response(), result.improved() ? result.answer() : null, null,
                result.totalCost(), result.totalMs());

        if (!result.improved()) {
            // Every attempt was discarded, so the original stands. Reported, not
            // hidden: an engine that never improves anything is one to turn off.
            return annotate(response, verdict, false);
        }
        return new GatewayDtos.ChatResponse(result.answer(), response.provider(), response.model(),
                response.latency() + result.totalMs(), response.tokens(),
                response.cost() + result.totalCost(), response.failovers(),
                response.routingReason() + String.format(" · repaired (%.2f → %.2f) over %d attempt%s",
                        result.originalScore(), result.finalScore(), result.attempts().size(),
                        result.attempts().size() == 1 ? "" : "s"),
                response.confidence(), response.lowConfidence(), response.agreementClusters());
    }

    /** Appends the verdict to the routing reason without altering the answer. */
    private static GatewayDtos.ChatResponse annotate(GatewayDtos.ChatResponse r,
                                                     io.continuum.quality.QualityGate.Verdict v,
                                                     boolean alreadyDescribed) {
        if (alreadyDescribed) {
            return r;
        }
        String note = v.passed()
                ? String.format(" · quality %.2f", v.score())
                : String.format(" · quality %.2f (%s): %s", v.score(),
                        v.action().name().toLowerCase(), v.summary());
        return new GatewayDtos.ChatResponse(r.response(), r.provider(), r.model(), r.latency(),
                r.tokens(), r.cost(), r.failovers(), r.routingReason() + note,
                r.confidence(), r.lowConfidence(), r.agreementClusters());
    }

    /**
     * Attaches a confidence measurement to a finished response.
     *
     * <p>Resamples the same question at a non-zero temperature and takes entropy
     * over the <em>meanings</em> of the samples, so a model that says the same
     * thing several ways reads as certain and one that says several different
     * things does not.
     *
     * <p>Returns the response untouched on any failure. The answer is already
     * good; losing it because a measurement failed would be absurd.
     */
    private GatewayDtos.ChatResponse withUncertainty(
            GatewayDtos.ChatResponse response, String developerId, GatewayDtos.ChatRequest req,
            LlmRequest canonical, String provider, String model, Map<String, String> devKeys,
            boolean judgeUnsure) {

        boolean requested = Boolean.TRUE.equals(req.measureUncertainty());
        if (!uncertainty.shouldMeasure(developerId, requested, judgeUnsure)) {
            return response;
        }
        try {
            var cfg = uncertainty.settingsFor(developerId);
            int extra = Math.max(1, cfg.getSamples() - 1);
            long start = System.nanoTime();

            List<String> answers = new ArrayList<>();
            answers.add(response.response());
            double extraCost = 0;
            io.continuum.uncertainty.AdaptiveStopping.Decision stopped = null;
            for (int i = 0; i < extra; i++) {
                LlmResponse r = chaos(developerId, router.complete(
                        new LlmRequest(model, canonical.messages(), canonical.maxTokens(),
                                cfg.getTemperature()),
                        List.of(provider), keyFor(devKeys, provider)));
                answers.add(r.content());
                extraCost += router.estimateCost(provider, r.model(), r.promptTokens(), r.completionTokens());

                // Adaptive consensus: stop as soon as the answer is decided
                // rather than always drawing the configured k. Off by default,
                // in which case this loop behaves exactly as it always has.
                if (cfg.isAdaptiveEnabled()) {
                    stopped = io.continuum.uncertainty.AdaptiveStopping.decide(
                            uncertainty.clusterSizes(answers), answers.size(), cfg.getSamples(),
                            cfg.getOverturnThreshold());
                    if (stopped.stop()) {
                        break;
                    }
                }
            }
            long extraMs = (System.nanoTime() - start) / 1_000_000;

            var m = uncertainty.measure(developerId, answers);
            uncertainty.record(developerId, lastUserContent(canonical), model, m, extraCost, extraMs);

            // Second drift channel. The quality gate checks whether an answer
            // honours its contract, which is deliberately not a check on whether
            // it is true — a fluent, well-formatted fabrication scores full
            // marks. Self-agreement is the signal that moves when a model starts
            // confabulating, so when it is being measured the breaker gets it
            // too.
            if (!Double.isNaN(m.confidence())) {
                breaker.observe(developerId, provider, model, m.confidence(),
                        m.clusters() > 1 ? m.clusters() + " conflicting answers across samples" : null);
            }

            return new GatewayDtos.ChatResponse(response.response(), response.provider(), response.model(),
                    response.latency() + extraMs, response.tokens(), response.cost() + extraCost,
                    response.failovers(),
                    response.routingReason() + String.format(" · confidence %.2f over %d samples in %d meaning%s",
                            m.confidence(), m.samples(), m.clusters(), m.clusters() == 1 ? "" : "s")
                            + (stopped != null && stopped.stop() && m.samples() < cfg.getSamples()
                                    ? String.format(" · stopped early, %d of %d drawn",
                                            m.samples(), cfg.getSamples()) : ""),
                    Double.isNaN(m.confidence()) ? null : m.confidence(),
                    m.lowConfidence(), m.clusters());
        } catch (Exception e) {
            log.warn("Uncertainty measurement failed for {}; returning the answer unmeasured: {}",
                    developerId, e.getMessage());
            return response;
        }
    }

    /**
     * Answers on the cheap tier, judges it, and escalates only if needed.
     *
     * <p>Returns {@code null} when the cascade cannot apply — no meaningful
     * price gap between models, or the cheap call failed — so the caller falls
     * through to the ordinary chain. The cascade is an optimisation and must
     * never be the reason a request fails.
     */
    private GatewayDtos.ChatResponse tryCascade(
            String developerId, GatewayDtos.ChatRequest req, LlmRequest canonical,
            Map<String, String> devKeys, double complexity, long started,
            io.continuum.routing.RoutingStrategyService.Decision routingDecision,
            java.util.Optional<io.continuum.autopilot.PolicyResolver.ResolvedPolicy> autopilot,
            String cacheKey) {

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
            cheapResp = chaos(developerId, router.complete(
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
                strongResp = chaos(developerId, router.complete(
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

        var savedLog = logRepo.save(new GatewayRequestLogEntity(developerId, req.model(), chosenProvider,
                chosen.model(), complexity, reason, totalMs, tokens, billed, true, 0));
        labelForAutopilot(autopilot, savedLog.getId(), developerId, true, totalMs, billed);
        recordBandit(complexity, chosenProvider, true, totalMs, billed);
        routingStrategy.record(developerId, routingDecision, complexity, chosenProvider, true, totalMs, billed);

        String safeContent = firewall.guardOutbound(developerId, chosen.content());
        godMode.observeExchange(developerId, "gateway", lastUserContent(canonical), safeContent);
        semanticCache.store(developerId, cacheKey, req.model(), chosenProvider, safeContent, tokens, billed);

        // The judge's ambivalent band is exactly where a second opinion is worth
        // buying, which is what ADAPTIVE mode targets.
        boolean judgeUnsure = assessment.confidence() < Math.min(1.0, assessment.threshold() + 0.15);
        String finalModel = served ? strong.model() : cheap.model();
        return withUncertainty(
                withQualityGate(
                        new GatewayDtos.ChatResponse(safeContent, chosenProvider, chosen.model(),
                                totalMs, tokens, billed, 0, reason),
                        developerId, canonical, chosenProvider, finalModel, devKeys, complexity),
                developerId, req, canonical, chosenProvider, finalModel, devKeys, judgeUnsure);
    }

    /**
     * Applies armed AI-level faults to a provider response.
     *
     * <p>Chaos is scoped to the tenant that armed it, so this is safe to run
     * against production traffic — and it is what makes a hallucination drill
     * visible on the path an external application actually uses.
     */
    private LlmResponse chaos(String developerId, LlmResponse response) {
        if (developerId == null || !aiChaos.isActive(developerId)) {
            return response;
        }
        try {
            return aiChaos.applyToResponse(response, "gateway:" + developerId, null);
        } catch (Exception e) {
            log.warn("AI chaos injection failed for {}; returning the response untouched: {}",
                    developerId, e.getMessage());
            return response;
        }
    }

    private static Map<String, String> keyFor(Map<String, String> devKeys, String provider) {
        return devKeys.containsKey(provider) ? Map.of(provider, devKeys.get(provider)) : null;
    }

    /**
     * Races the top providers in the chain and returns the first good answer.
     *
     * <p>Returns {@code null} rather than throwing when the race produces
     * nothing usable, so the caller falls back to the ordinary sequential chain.
     * Hedging is an optimisation; it must never be the reason a request fails.
     */
    private GatewayDtos.ChatResponse tryHedged(
            String developerId, GatewayDtos.ChatRequest req, LlmRequest canonical,
            List<ModelFallbackPolicy.ModelCandidate> chain, Map<String, String> devKeys,
            double complexity, RoutingMode mode, long started,
            io.continuum.routing.RoutingStrategyService.Decision routingDecision,
            java.util.Optional<io.continuum.autopilot.PolicyResolver.ResolvedPolicy> autopilot,
            String cacheKey) {

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
                        return router.complete(new LlmRequest(cand.model(), request.messages(),
                                request.maxTokens(), request.temperature()), List.of(provider), keys);
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

            var savedLog = logRepo.save(new GatewayRequestLogEntity(developerId, req.model(), winner,
                    resp.model(), complexity, reason, totalMs, tokens, billedCost, true, 0));
            labelForAutopilot(autopilot, savedLog.getId(), developerId, true, totalMs, billedCost);
            recordBandit(complexity, winner, true, totalMs, billedCost);
            routingStrategy.record(developerId, routingDecision, complexity, winner, true, totalMs, billedCost);

            String safeContent = firewall.guardOutbound(developerId, resp.content());
            godMode.observeExchange(developerId, "gateway", lastUserContent(canonical), safeContent);
            semanticCache.store(developerId, cacheKey, req.model(), winner, safeContent, tokens, billedCost);

            return new GatewayDtos.ChatResponse(safeContent, winner, resp.model(),
                    totalMs, tokens, billedCost, 0, reason);
        } catch (Exception e) {
            log.warn("Hedged execution failed for {}; falling back to the sequential chain: {}",
                    developerId, e.getMessage());
            return null;
        }
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


    /**
     * A slot at the provider, or null when admission control is off.
     *
     * <p>A shed request is not a failover. Falling over to the next provider
     * because this one is busy is how a local overload becomes a global one, so
     * the refusal propagates to the caller immediately and says why.
     */
    private AdmissionService.Slot admissionSlot(String developerId, String provider,
                                                GatewayDtos.ChatRequest req) {
        if (!admission.enabled(developerId)) {
            return null;
        }
        return admission.acquire(developerId, provider, Criticality.of(req.criticality()));
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
