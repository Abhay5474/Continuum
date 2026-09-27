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
 * What happens to an answer after a provider gave it: the quality gate (and
 * repair, when enforcing), then the confidence measurement. Gate first, then
 * measure: there is no point measuring an answer that is about to be replaced.
 */
@org.springframework.stereotype.Component
public class AnswerReview {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnswerReview.class);

    private final ProviderRouter router;
    private final io.continuum.quality.AnswerRepairService repairEngine;
    private final io.continuum.firewall.PromptFirewallService firewall;
    private final io.continuum.uncertainty.SemanticUncertaintyService uncertainty;
    private final io.continuum.quality.QualityGate qualityGate;
    private final io.continuum.quality.QualityGateService quality;
    private final io.continuum.drift.SemanticBreakerService breaker;
    private final ResponseChaos chaos;

    public AnswerReview(ProviderRouter router, io.continuum.quality.AnswerRepairService repairEngine, io.continuum.firewall.PromptFirewallService firewall, io.continuum.uncertainty.SemanticUncertaintyService uncertainty, io.continuum.quality.QualityGate qualityGate, io.continuum.quality.QualityGateService quality, io.continuum.drift.SemanticBreakerService breaker, ResponseChaos chaos) {
        this.router = router;
        this.repairEngine = repairEngine;
        this.firewall = firewall;
        this.uncertainty = uncertainty;
        this.qualityGate = qualityGate;
        this.quality = quality;
        this.breaker = breaker;
        this.chaos = chaos;
    }
    /**
     * Runs the quality gate over a finished answer, repairing it if enforcing.
     *
     * <p>Returns the response unchanged on anything unexpected, and on a repair
     * that runs out of budget. The answer already exists; discarding it because
     * a check failed to complete would be the wrong trade every time.
     */
    public GatewayDtos.ChatResponse withQualityGate(
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

            LlmResponse repaired = chaos.apply(developerId, router.complete(
                    // The caller's response format is kept: a repaired JSON
                    // answer must still be JSON. Tools are not offered to a
                    // repair — it rewrites text, it does not act.
                    new LlmRequest(model, repairMessages, canonical.maxTokens(), canonical.temperature(),
                            null, null, canonical.responseFormat()),
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
            return annotate(response.withRevision(safe, repairMs, repairCost,
                    String.format(" · repaired (%.2f → %.2f): %s",
                            verdict.score(), after.score(), verdict.summary())),
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
    public GatewayDtos.ChatResponse withRepairEngine(
            GatewayDtos.ChatResponse response, String developerId, LlmRequest canonical,
            String provider, String model, Map<String, String> devKeys, double complexity,
            io.continuum.persistence.entity.QualityGateSettingEntity cfg,
            io.continuum.quality.QualityGate.Verdict verdict) {

        var result = repairEngine.repair(developerId, model, canonical, response.response(),
                verdict, complexity, cfg.getThreshold(), cfg.getMaxRepairs(), cfg.getBudgetMs(),
                messages -> {
                    LlmResponse r = chaos.apply(developerId, router.complete(
                            new LlmRequest(model, messages, canonical.maxTokens(),
                                    canonical.temperature(), null, null, canonical.responseFormat()),
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
        return response.withRevision(result.answer(), result.totalMs(), result.totalCost(),
                String.format(" · repaired (%.2f → %.2f) over %d attempt%s",
                        result.originalScore(), result.finalScore(), result.attempts().size(),
                        result.attempts().size() == 1 ? "" : "s"));
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
    public GatewayDtos.ChatResponse withUncertainty(
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
                LlmResponse r = chaos.apply(developerId, router.complete(
                        new LlmRequest(model, canonical.messages(), canonical.maxTokens(),
                                cfg.getTemperature(), null, null, canonical.responseFormat()),
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

            return response.withConfidence(
                    Double.isNaN(m.confidence()) ? null : m.confidence(), m.lowConfidence(), m.clusters(),
                    extraMs, extraCost,
                    String.format(" · confidence %.2f over %d samples in %d meaning%s",
                            m.confidence(), m.samples(), m.clusters(), m.clusters() == 1 ? "" : "s")
                            + (stopped != null && stopped.stop() && m.samples() < cfg.getSamples()
                                    ? String.format(" · stopped early, %d of %d drawn",
                                            m.samples(), cfg.getSamples()) : ""));
        } catch (Exception e) {
            log.warn("Uncertainty measurement failed for {}; returning the answer unmeasured: {}",
                    developerId, e.getMessage());
            return response;
        }
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
        return r.withNote(note);
    }
}
