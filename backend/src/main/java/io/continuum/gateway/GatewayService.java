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

    /** Most model attempts one request may make before giving up. */
    static final int MAX_MODEL_ATTEMPTS = 6;
    /** Most models of one provider one request may try. */
    static final int MAX_MODELS_PER_PROVIDER = 3;

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
    private final SchedulerService scheduler;
    private final CostAdmissionService costAdmission;
    private final io.continuum.quality.AnswerRepairService repairEngine;
    private final io.continuum.provenance.ProvenanceService provenance;
    private final io.continuum.degradation.DegradationService degradation;
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
    private final io.continuum.observability.GatewayMetrics metrics;
    private final io.continuum.firewall.PromptFirewallService firewall;
    // The context layer on the chat path (opt-in, OFF by default). Pipelines
    // have always transformed a recognised payload; this is the same thing for
    // /v1/chat/completions, which is the endpoint most callers use.
    private final io.continuum.context.PromptContextService promptContext;
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

    // The stages the request passes through, each its own class. Setter-free
    // field injection keeps the (already long) constructor unchanged.
    @org.springframework.beans.factory.annotation.Autowired private RequestLog requestLog;
    @org.springframework.beans.factory.annotation.Autowired private ResponseChaos responseChaos;
    @org.springframework.beans.factory.annotation.Autowired private AnswerBookkeeping bookkeeping;
    @org.springframework.beans.factory.annotation.Autowired private AnswerReview review;
    @org.springframework.beans.factory.annotation.Autowired private CascadeStage cascadeStage;
    @org.springframework.beans.factory.annotation.Autowired private HedgeStage hedgeStage;

    public GatewayService(RequestNormalizer normalizer, TaskComplexityEstimator complexityEstimator,
                          ProviderSelectionEngine selectionEngine, ModelRegistryService registry,
                          ModelFallbackPolicy fallbackPolicy, ProviderHealthTracker health,
                          CredentialVaultService vault, ProviderRouter router,
                          AdmissionService admission,
                          SchedulerService scheduler,
                          CostAdmissionService costAdmission,
                          io.continuum.quality.AnswerRepairService repairEngine,
                          io.continuum.provenance.ProvenanceService provenance,
                          io.continuum.degradation.DegradationService degradation,
                          GatewayRequestLogRepository logRepo,
                          io.continuum.persistence.repository.DeveloperAuthRepository devAuth,
                          io.continuum.autopilot.PolicyResolver policyResolver,
                          io.continuum.persistence.repository.AutopilotRequestLabelRepository autopilotLabels,
                          io.continuum.godmode.GodModeService godMode,
                          io.continuum.dag.ConsensusDagService consensusDag,
                          io.continuum.mmu.ContextMMU contextMmu,
                          io.continuum.autopilot.engine.ContextualBanditEngine contextualBandit,
                          io.continuum.compression.PromptCompressionService compression,
                          io.continuum.observability.GatewayMetrics metrics,
                          io.continuum.firewall.PromptFirewallService firewall,
                          io.continuum.context.PromptContextService promptContext,
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
        this.scheduler = scheduler;
        this.costAdmission = costAdmission;
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
        this.provenance = provenance;
        this.degradation = degradation;
        this.logRepo = logRepo;
        this.devAuth = devAuth;
        this.policyResolver = policyResolver;
        this.autopilotLabels = autopilotLabels;
        this.godMode = godMode;
        this.consensusDag = consensusDag;
        this.contextMmu = contextMmu;
        this.contextualBandit = contextualBandit;
        this.compression = compression;
        this.metrics = metrics;
        this.firewall = firewall;
        this.promptContext = promptContext;
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


    /**
     * Cost-aware admission wraps the request, when the tenant has enabled it.
     *
     * <p>The reservation is taken on the prompt as <em>submitted</em>, because
     * that is the only size known before any work happens, and settled on the
     * provider's reported usage — which reflects compression, paging and cache
     * hits. The reserve is a conservative gate; settlement is what makes the
     * accounting true.
     *
     * <p>The finally block is not optional: a reservation that is never returned
     * holds allowance nobody is using until it times out.
     */
    /**
     * Like {@link #chat(String, GatewayDtos.ChatRequest)}, handing the answer to
     * {@code sink} as the provider generates it.
     *
     * <p>Only the ordinary provider call streams. A cached answer, a cascaded
     * or hedged one, one that the MMU pages or AI chaos rewrites, or one with
     * tool calls arrives whole and is not handed to the sink at all — the
     * caller sends it as it would a buffered answer. {@link #streamedLive}
     * says which happened.
     */
    public GatewayDtos.ChatResponse chat(String developerId, GatewayDtos.ChatRequest req,
                                         io.continuum.provider.TokenSink sink) {
        LIVE.set(sink);
        try {
            return chat(developerId, req);
        } finally {
            LIVE.remove();
        }
    }

    /** The caller's sink while a streamed request runs on this thread, else null. */
    private static final ThreadLocal<io.continuum.provider.TokenSink> LIVE = new ThreadLocal<>();

    public GatewayDtos.ChatResponse chat(String developerId, GatewayDtos.ChatRequest req) {
        requestLog.clear();
        try {
            GatewayDtos.ChatResponse response = admitted(developerId, req);
            Long id = requestLog.lastId();
            return response == null || id == null ? response : response.withRequestId(requestId(id));
        } catch (GatewayException e) {
            // A failed request is logged too; its id is what a caller quotes.
            Long id = requestLog.lastId();
            if (id != null && e.requestId == null) {
                e.requestId = requestId(id);
            }
            throw e;
        } finally {
            requestLog.clear();
        }
    }

    /** The public form of a logged request's id, as callers and the console show it. */
    public static String requestId(long logId) {
        return "req_" + logId;
    }



    private GatewayDtos.ChatResponse admitted(String developerId, GatewayDtos.ChatRequest req) {
        CostAwareLimiter.Ticket ticket = costAdmission.reserve(
                developerId, promptTokensOf(req), req == null ? null : req.maxTokens());
        if (ticket == null) {
            return chatInner(developerId, req);
        }
        try {
            GatewayDtos.ChatResponse response = chatInner(developerId, req);
            ticket.settle(response == null ? 0 : response.tokens());
            return response;
        } finally {
            // No-op after a settle; returns the whole reservation otherwise.
            ticket.close();
        }
    }

    /**
     * Records a context stage in the trail — only when it changed the prompt,
     * so an untouched request does not carry five "nothing happened" lines —
     * with the token count before and after: what the stage cost or saved.
     */
    private static void contextStep(io.continuum.provenance.ProvenanceService.Recording prov,
                                    io.continuum.provenance.Decision.Stage stage, String choice,
                                    LlmRequest before, LlmRequest after, String what) {
        if (prov == null || before == after || before == null || after == null
                || java.util.Objects.equals(before.messages(), after.messages())) {
            return;
        }
        int in = tokensOf(before);
        int out = tokensOf(after);
        int d = Math.abs(in - out);
        String delta = in == out ? "same size"
                : d + (out < in ? " fewer" : " more") + (d == 1 ? " token" : " tokens");
        prov.add(stage, choice, what + " (" + in + " → " + out + " tokens, " + delta + ")");
    }

    private static int tokensOf(LlmRequest r) {
        int n = 0;
        if (r.messages() != null) {
            for (var m : r.messages()) {
                n += io.continuum.compression.PromptCompressor.estimateTokens(m.content());
            }
        }
        return n;
    }

    /** Tokens the caller sent us, before Continuum shrinks anything. */
    private static int promptTokensOf(GatewayDtos.ChatRequest req) {
        if (req == null || req.messages() == null) {
            return 0;
        }
        int n = 0;
        for (GatewayDtos.Message m : req.messages()) {
            n += io.continuum.compression.PromptCompressor.estimateTokens(m.content());
        }
        return n;
    }

    private GatewayDtos.ChatResponse chatInner(String developerId, GatewayDtos.ChatRequest req) {
        // Billing: reject requests once the monthly token quota is exhausted
        // (throws QuotaExceededException → mapped to 402 upstream). The default
        // FREE plan quota is generous, so this is transparent for normal use.
        billing.assertWithinQuota(developerId);
        // Validate before any path runs. The DAG branch below used to take the
        // raw request, so with it enabled a request with no messages skipped
        // the normalizer's check and a whole verification ran on an empty prompt.
        LlmRequest canonical = normalizer.normalize(req);
        final LlmRequest unguarded = canonical;
        boolean firewalled = false;
        // V6 Consensus DAG Engine (opt-in, OFF by default): when the developer
        // enabled it in the portal, the request is verified through the DAG and
        // returned in the identical response shape. When the flag is off — or
        // the DAG fails for any reason — everything below is the exact legacy path.
        if (consensusDag.enabledFor(developerId)) {
            // The firewall still goes first. This branch used to run before
            // it, so turning verification on sent unredacted personal data and
            // unscreened injection attempts to every provider the DAG called.
            canonical = firewall.guardInbound(developerId, canonical);
            firewalled = true;
            GatewayDtos.ChatResponse verified = null;
            try {
                verified = consensusDag.run(developerId, req, lastUserContent(canonical));
            } catch (Exception e) {
                log.warn("V6 DAG run failed for {}, falling back to legacy path: {}",
                        developerId, e.getMessage());
            }
            if (verified != null) {
                // Logged like every other request. This path used to return
                // without a row, so verified requests were missing from usage,
                // spend and the request feed — and were never counted against
                // the monthly token quota. Kept apart from the run itself, so
                // a logging failure cannot send the request down a second path.
                try {
                    requestLog.save(new GatewayRequestLogEntity(developerId, req.model(), verified.provider(),
                            verified.model(), 0, verified.routingReason(), verified.latency(),
                            verified.tokens(), verified.cost(), true, verified.failovers()));
                } catch (RuntimeException e) {
                    log.warn("Could not log verified request for {}: {}", developerId, e.getMessage());
                }
                return verified;
            }
        }
        long started = System.nanoTime();
        // Provenance: null unless the tenant turned it on. Opened here, before
        // anything touches the prompt, so the trail says what happened to the
        // context — redacted, transformed, paged, compressed, served from
        // cache — and not only which model answered. Those stages always ran;
        // the trail used to start after them.
        io.continuum.provenance.ProvenanceService.Recording prov = provenance.start(developerId);
        LlmRequest before = unguarded;
        // V8 Prompt Firewall (opt-in, OFF by default): redact PII and block
        // prompt-injection BEFORE anything else touches the prompt. Pass-through
        // when off. A blocked request throws (mapped to a clean 4xx upstream).
        // Already applied when the request came back from a failed DAG run.
        if (!firewalled) {
            canonical = firewall.guardInbound(developerId, canonical);
        }
        contextStep(prov, io.continuum.provenance.Decision.Stage.FIREWALL, "redacted", before, canonical,
                "sensitive content was masked before any other stage saw the prompt");
        before = canonical;

        // The context layer (opt-in, OFF by default): a spreadsheet, log or
        // email thread pasted into a message becomes its canonical form before
        // the model sees it — the same transformation pipelines have always
        // done, on the endpoint most callers actually use. Pass-through when
        // off and when nothing is recognised.
        //
        // After the firewall, so the transformer never sees unredacted PII and
        // the security control stays first. Before the cache, so two callers
        // who paste the same table worded differently share a cache entry —
        // the key is taken from the text below, which is now the canonical
        // form rather than whatever formatting each of them happened to use.
        canonical = promptContext.maybeTransform(developerId, canonical);
        contextStep(prov, io.continuum.provenance.Decision.Stage.CONTEXT, "transformed", before, canonical,
                "a pasted table, log or thread was rewritten into its canonical form");

        // Semantic cache (opt-in, OFF by default): if this question has already
        // been answered, return that answer instead of paying a provider for it
        // again. Placed after the firewall so a cached prompt is already
        // redacted, and before routing/paging/compression — all of which exist
        // to serve the call we are about to skip.
        String cacheKey = lastUserContent(canonical);
        if (semanticCache.enabledFor(developerId)) {
            var hit = semanticCache.lookup(developerId, cacheKey, req.model());
            metrics.cache(hit.isPresent());
            if (prov != null) {
                prov.add(io.continuum.provenance.Decision.Stage.CACHE, hit.isPresent() ? "hit" : "miss",
                        hit.map(h -> String.format("%s match, similarity %.2f — no provider was called",
                                h.exact() ? "exact" : "near", h.similarity()))
                                .orElse("no earlier answer close enough; the request goes to a model"));
            }
            if (hit.isPresent()) {
                var h = hit.get();
                long cachedMs = (System.nanoTime() - started) / 1_000_000;
                if (prov != null) {
                    prov.commit();
                }
                // Logged like any other request, with zero cost, so usage and
                // spend reporting stay truthful about what the cache avoided.
                requestLog.save(new GatewayRequestLogEntity(developerId, req.model(), h.provider(),
                        h.model(), 0, "semantic-cache", cachedMs, 0, 0, true, 0)
                        .withTraceId(prov == null ? null : prov.requestId()));
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
        before = canonical;
        if (mmuSession != null) {
            canonical = mmuSession.request();
            contextStep(prov, io.continuum.provenance.Decision.Stage.CONTEXT, "paged", before, canonical,
                    "older turns were paged out to semantic stubs and fault back in if referenced");
        }
        before = canonical;
        // V8 Prompt Compression (opt-in, OFF by default): shrink context tokens.
        // Pass-through when off; runs after paging so it compresses the final context.
        canonical = compression.maybeCompress(developerId, canonical);
        contextStep(prov, io.continuum.provenance.Decision.Stage.CONTEXT, "compressed", before, canonical,
                "low-information tokens were dropped from the final context");
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

        // Provenance: the same facts the routing reason concatenates, as data.
        // Null unless the tenant turned it on. Recorded before the cascade and
        // hedging, which answer on their own and used to return before this.
        if (prov != null) {
            prov.add(io.continuum.provenance.Decision.Stage.COMPLEXITY,
                    String.format("%.2f", complexity),
                    "estimated from the request before any model was chosen");
            prov.add(new io.continuum.provenance.Decision(
                    io.continuum.provenance.Decision.Stage.ROUTE,
                    chain.isEmpty() ? "none" : chain.get(0).model(),
                    "mode=" + mode + ", " + chain.size() + " candidate"
                            + (chain.size() == 1 ? "" : "s") + " in the fallback chain",
                    chain.stream().skip(1).map(ModelFallbackPolicy.ModelCandidate::model).toList(),
                    0, 0));
        }

        // Verify-then-escalate cascade (per-tenant, opt-in, OFF by default).
        // Runs ahead of hedging and the ordinary chain: when it produces an
        // answer, nothing below needs to. Declines silently when the registry
        // offers no meaningful price difference between models.
        if (cascade.enabledFor(developerId)) {
            GatewayDtos.ChatResponse cascaded = cascadeStage.attempt(developerId, req, canonical, devKeys,
                    complexity, started, routingDecision, autopilot, cacheKey, prov);
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
            GatewayDtos.ChatResponse hedged = hedgeStage.attempt(developerId, req, canonical, chain, devKeys,
                    complexity, mode, started, routingDecision, autopilot, cacheKey, prov);
            if (hedged != null) {
                return hedged;
            }
            // A failed race is not a failed request: fall through to the
            // ordinary sequential chain, which is the behaviour without hedging.
        }


        int failovers = 0;
        RuntimeException lastError = null;
        // Providers that rejected the credential on this request. The chain lists
        // several models per provider, and a key Gemini refuses for one model it
        // refuses for all of them: trying each cost a round trip apiece and
        // delayed a failover that was going to happen anyway.
        java.util.Set<String> rejectedCredential = new java.util.HashSet<>();
        // Bounded. The chain lists every routable model, and with whole provider
        // catalogues that can be dozens: a request that fails everywhere must
        // not turn into dozens of calls against the free quota. The bounds match
        // the worst case before the catalogue (two models per provider).
        int attempts = 0;
        Map<String, Integer> perProvider = new HashMap<>();
        for (ModelFallbackPolicy.ModelCandidate c : chain) {
            if (rejectedCredential.contains(c.provider())) {
                continue;
            }
            if (attempts >= MAX_MODEL_ATTEMPTS) {
                break;
            }
            if (perProvider.getOrDefault(c.provider(), 0) >= MAX_MODELS_PER_PROVIDER) {
                continue;
            }
            attempts++;
            perProvider.merge(c.provider(), 1, Integer::sum);
            // withModel, not a fresh four-argument request: rebuilding it that way
            // silently dropped the caller's tools and response_format, so a
            // tool-calling client got prose back and could not tell why.
            LlmRequest perModel = canonical.withModel(c.model());
            Map<String, String> keys = devKeys.containsKey(c.provider())
                    ? Map.of(c.provider(), devKeys.get(c.provider())) : null;
            long attemptStart = System.nanoTime();
            // Congestion-controlled admission. The slot is held for exactly the
            // provider call: holding it across the quality gate or the cascade
            // would count time the provider is not busy against its capacity.
            AdmissionService.Slot slot = admissionSlot(developerId, c.provider(), req);
            try {
                // Streamed only when nothing below rewrites the answer: the MMU
                // may re-dispatch it, and AI chaos may corrupt it on purpose.
                io.continuum.provider.TokenSink live = LIVE.get();
                boolean streamNow = live != null && mmuSession == null
                        && (developerId == null || !aiChaos.isActive(developerId));
                LlmResponse resp = streamNow
                        ? router.stream(perModel, List.of(c.provider()), keys, live)
                        : responseChaos.apply(developerId, router.complete(perModel, List.of(c.provider()), keys));
                if (slot != null) {
                    slot.success();
                }
                if (mmuSession != null) {
                    // V7 page-fault interception: if the model requested a paged
                    // segment, materialize it from L3 and re-dispatch (bounded).
                    final Map<String, String> faultKeys = keys;
                    final String provider = c.provider();
                    final String model = c.model();
                    // Still the caller's request, so it keeps their tools.
                    resp = mmuSession.interceptFaults(resp, r -> router.complete(
                            r.withModel(model), List.of(provider), faultKeys));
                    mmuSession.finish(resp);
                }
                long attemptMs = (System.nanoTime() - attemptStart) / 1_000_000;
                health.recordSuccess(c.provider(), c.model(), attemptMs);
                if (prov != null) {
                    prov.add(new io.continuum.provenance.Decision(
                            io.continuum.provenance.Decision.Stage.PROVIDER,
                            c.provider() + "/" + c.model(),
                            failovers == 0 ? "first choice answered"
                                    : "answered after " + failovers + " failover"
                                            + (failovers == 1 ? "" : "s"),
                            List.of(), 0, attemptMs));
                }

                int tokens = resp.promptTokens() + resp.completionTokens();
                double cost = router.estimateCost(c.provider(), resp.model(),
                        resp.promptTokens(), resp.completionTokens());
                long totalMs = (System.nanoTime() - started) / 1_000_000;
                String reason = routingReason(complexity, mode, c, failovers);

                if (prov != null) {
                    prov.add(new io.continuum.provenance.Decision(
                            io.continuum.provenance.Decision.Stage.OUTPUT,
                            String.valueOf(tokens) + " tokens",
                            reason, List.of(), cost, totalMs));
                    prov.commit();
                }
                // Published before the log write, so a slow database cannot make
                // the latency metric report its own contention as provider time.
                metrics.request(c.provider(), resp.model(), true, totalMs);
                metrics.overhead(c.provider(), totalMs, attemptMs);
                metrics.tokens(c.provider(), resp.promptTokens(), resp.completionTokens());
                metrics.cost(c.provider(), cost);

                var savedLog = requestLog.save(new GatewayRequestLogEntity(developerId, req.model(), c.provider(),
                        resp.model(), complexity, reason, totalMs, tokens, cost, true, failovers)
                        .withTraceId(prov == null ? null : prov.requestId()));
                bookkeeping.labelForAutopilot(autopilot, savedLog.getId(), developerId, true, totalMs, cost);
                bookkeeping.recordBandit(complexity, c.provider(), true, totalMs, cost);
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
                // The model's tool calls and its token split travel with the
                // answer. Both were being dropped here: a caller whose model
                // asked to call a function got a 200 with empty prose, which is
                // indistinguishable from the model having refused.
                GatewayDtos.ChatResponse answered =
                        completed(new GatewayDtos.ChatResponse(safeContent, c.provider(), resp.model(),
                                totalMs, tokens, cost, failovers, reason), resp);

                return review.withUncertainty(
                        review.withQualityGate(answered,
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
                metrics.failover(c.provider(), e.getClass().getSimpleName());
                bookkeeping.recordBandit(complexity, c.provider(), false, attemptMs, 0);
                routingStrategy.record(developerId, routingDecision, complexity,
                        c.provider(), false, attemptMs, 0);
                failovers++;
                if (prov != null) {
                    prov.add(io.continuum.provenance.Decision.Stage.PROVIDER,
                            c.provider() + "/" + c.model() + " (failed)",
                            "failing over: " + e.getMessage());
                }
                lastError = new RuntimeException(e.getMessage(), e);
                if (e instanceof io.continuum.provider.ProviderRouter.StreamBrokenException broken) {
                    // Part of an answer is already with the caller; another
                    // provider's answer cannot be appended to it.
                    throw broken;
                }
                if (isCredentialRejection(e)) {
                    rejectedCredential.add(c.provider());
                }
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
        metrics.request("none", req.model(), false, totalMs);
        metrics.visibleFailure("all_providers_failed");
        var failLog = logFailure(developerId, req, complexity, mode);
        bookkeeping.labelForAutopilot(autopilot, failLog == null ? null : failLog.getId(), developerId, false, totalMs, 0);

        // Graceful degradation. Every provider failed; with the ladder on, step
        // down to something rather than returning nothing. The response always
        // says which rung it came from — a degraded answer presented as a normal
        // one is worse than an error, because the caller cannot tell it should
        // retry or warn its user.
        if (degradation.enabled(developerId)) {
            String cached = null;
            try {
                var stale = semanticCache.lookup(developerId, cacheKey, req.model());
                cached = stale.map(io.continuum.cache.SemanticCacheService.Hit::response).orElse(null);
            } catch (RuntimeException e) {
                log.debug("Degradation cache lookup failed: {}", e.getMessage());
            }
            var outcome = io.continuum.degradation.DegradationLadder.descend(
                    cached, lastError == null ? null : lastError.getMessage());
            degradation.record(developerId, outcome);
            if (prov != null) {
                prov.add(io.continuum.provenance.Decision.Stage.OUTPUT,
                        "degraded:" + outcome.rung().name(), outcome.reason());
                prov.commit();
            }
            return new GatewayDtos.ChatResponse(outcome.answer(), "continuum",
                    "degraded/" + outcome.rung().name().toLowerCase(), totalMs, 0, 0, failovers,
                    "DEGRADED (" + outcome.rung().name() + "): " + outcome.reason());
        }

        if (prov != null) {
            prov.add(io.continuum.provenance.Decision.Stage.OUTPUT, "failed",
                    "every provider in the chain failed");
            prov.commit();
        }
        throw new GatewayException("All eligible providers failed for this request"
                + (lastError != null ? ": " + lastError.getMessage() : ""));
    }





    /**
     * Whether a provider failure was it refusing the key rather than the model.
     *
     * <p>Matched on the provider's own words as well as the status, because
     * Google reports a bad key as 400 rather than 401.
     */
    static boolean isCredentialRejection(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String m = t.getMessage();
            if (m != null && (m.contains("HTTP 401") || m.contains("HTTP 403")
                    || m.contains("API_KEY_INVALID") || m.contains("invalid_api_key")
                    || m.contains("Invalid API Key"))) {
                return true;
            }
        }
        return false;
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
            return requestLog.save(new GatewayRequestLogEntity(developerId, req.model(), null, null,
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
        Criticality criticality = Criticality.of(req.criticality());
        if (!scheduler.enabled(developerId)) {
            return admission.acquire(developerId, provider, criticality);
        }
        // Ordering only applies while something is actually waiting, so the
        // ticket lives exactly as long as the wait does.
        SchedulerService.Ticket ticket = scheduler.enqueue(developerId, provider,
                priorityOf(criticality), req.deadlineMs(),
                Math.round(admission.observedLatencyMs(developerId, provider)),
                java.time.Instant.now());
        try {
            return admission.acquire(developerId, provider, criticality, ticket);
        } finally {
            ticket.close();
        }
    }

    /**
     * Importance and scheduling priority are the same judgement seen twice: one
     * decides who is refused when there is no room, the other who goes first
     * when there is nearly none. Deriving the second from the first keeps a
     * caller from having to state it twice and disagree with itself.
     */
    private static DeadlineScheduler.Priority priorityOf(Criticality c) {
        return switch (c) {
            case BACKGROUND -> DeadlineScheduler.Priority.BATCH;
            case CRITICAL -> DeadlineScheduler.Priority.INTERACTIVE;
            default -> DeadlineScheduler.Priority.NORMAL;
        };
    }




    /**
     * The models a caller may name on {@code /v1/models}.
     *
     * <p>Exposed from here rather than from the router so the OpenAI surface has
     * one dependency instead of two, and so "what can I ask for" always matches
     * what routing would actually accept.
     */
    public java.util.List<String> routableModelNames() {
        try {
            return router.availableChain();
        } catch (RuntimeException e) {
            log.debug("Model listing unavailable: {}", e.getMessage());
            return java.util.List.of();
        }
    }

    /*
     * On the other `new LlmRequest(...)` sites in this class: the judge, the
     * repair pass, the cascade tiers and the probe all send prompts Continuum
     * wrote, about the caller's answer. They deliberately do NOT inherit the
     * caller's tools or response_format — a judge offered `get_weather` may call
     * it instead of scoring, and a scorer forced into json_object returns a
     * verdict in the caller's schema rather than its own. Dropping them there is
     * the correct behaviour, not the same bug as line 415 was.
     */

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
        /** The logged request's id, when it got as far as being logged. */
        public String requestId;

        public GatewayException(String message) {
            super(message);
        }
    }
}
