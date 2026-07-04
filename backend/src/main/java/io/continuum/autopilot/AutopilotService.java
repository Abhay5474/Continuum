package io.continuum.autopilot;

import io.continuum.autopilot.engine.CanaryEvaluator;
import io.continuum.autopilot.engine.DecisionEngine;
import io.continuum.autopilot.engine.PolicyVerifier;
import io.continuum.autopilot.model.AutopilotMode;
import io.continuum.autopilot.model.DeveloperProfile;
import io.continuum.autopilot.model.PolicyBundle;
import io.continuum.autopilot.model.PolicyStatus;
import io.continuum.common.Json;
import io.continuum.persistence.entity.*;
import io.continuum.persistence.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the Autopilot closed loop and all developer-facing actions. Every
 * mutation is bounded (only policy bundles), versioned, reversible and audited.
 */
@Service
public class AutopilotService {

    private static final Logger log = LoggerFactory.getLogger(AutopilotService.class);

    private final AutopilotConfigRepository configRepo;
    private final PolicyBundleService bundles;
    private final TelemetryAggregator aggregator;
    private final DecisionEngine decisionEngine;
    private final PolicyVerifier verifier;
    private final CanaryEvaluator canaryEvaluator;
    private final Json json;

    private final AutopilotDecisionRepository decisions;
    private final AutopilotRecommendationRepository recommendations;
    private final AutopilotCanaryRunRepository canaries;
    private final AutopilotRollbackRepository rollbacks;
    private final AutopilotFeedbackRepository feedback;
    private final AutopilotTelemetryRepository telemetry;
    private final AutopilotRequestLabelRepository labels;

    // Optional pre-flight gate (V5 God Mode digital twin). Absent/lazy ⇒ V4 behavior.
    private final org.springframework.beans.factory.ObjectProvider<CanaryPreflight> canaryPreflight;

    public AutopilotService(AutopilotConfigRepository configRepo, PolicyBundleService bundles,
                            TelemetryAggregator aggregator, DecisionEngine decisionEngine,
                            PolicyVerifier verifier, CanaryEvaluator canaryEvaluator, Json json,
                            AutopilotDecisionRepository decisions, AutopilotRecommendationRepository recommendations,
                            AutopilotCanaryRunRepository canaries, AutopilotRollbackRepository rollbacks,
                            AutopilotFeedbackRepository feedback, AutopilotTelemetryRepository telemetry,
                            AutopilotRequestLabelRepository labels,
                            org.springframework.beans.factory.ObjectProvider<CanaryPreflight> canaryPreflight) {
        this.configRepo = configRepo;
        this.bundles = bundles;
        this.aggregator = aggregator;
        this.decisionEngine = decisionEngine;
        this.verifier = verifier;
        this.canaryEvaluator = canaryEvaluator;
        this.json = json;
        this.decisions = decisions;
        this.recommendations = recommendations;
        this.canaries = canaries;
        this.rollbacks = rollbacks;
        this.feedback = feedback;
        this.telemetry = telemetry;
        this.labels = labels;
        this.canaryPreflight = canaryPreflight;
    }

    // ---- config / toggle ----

    @Transactional
    public AutopilotConfigEntity getOrCreateConfig(String developerId) {
        return configRepo.findById(developerId).orElseGet(() -> configRepo.save(new AutopilotConfigEntity(developerId)));
    }

    @Transactional
    public AutopilotConfigEntity setEnabled(String developerId, boolean enabled, String mode, DeveloperProfile profile) {
        AutopilotConfigEntity config = getOrCreateConfig(developerId);
        config.setEnabled(enabled);
        if (mode != null) {
            config.setMode(mode);
        }
        if (profile != null) {
            config.setProfileJson(json.write(profile));
        }
        if (enabled && config.getActiveBundleId() == null) {
            DeveloperProfile p = profileOf(config);
            PolicyBundle initial = PolicyBundle.defaultFor(p.mode(), p.allowedProviders(),
                    p.maxCostPerRequest(), p.maxLatencyMs());
            PolicyBundleEntity bundle = bundles.create(developerId, initial, PolicyStatus.ACTIVE, null, "SEED",
                    "Initial beginner-friendly policy");
            config.setActiveBundleId(bundle.getId());
        }
        configRepo.save(config);
        record(developerId, enabled ? "ENABLE" : "DISABLE",
                "Autopilot " + (enabled ? "enabled" : "disabled"), null, config.getActiveBundleId(), 1.0);
        return config;
    }

    @Transactional
    public void setAutoApply(String developerId, boolean autoApply) {
        AutopilotConfigEntity config = getOrCreateConfig(developerId);
        config.setAutoApply(autoApply);
        configRepo.save(config);
    }

    @Transactional
    public void setProfile(String developerId, DeveloperProfile profile) {
        AutopilotConfigEntity config = getOrCreateConfig(developerId);
        config.setProfileJson(json.write(profile));
        config.setMode(profile.mode().name());
        configRepo.save(config);
    }

    public DeveloperProfile profileOf(AutopilotConfigEntity config) {
        DeveloperProfile base = config.getProfileJson() == null
                ? DeveloperProfile.beginnerDefault() : json.read(config.getProfileJson(), DeveloperProfile.class);
        AutopilotMode mode;
        try {
            mode = AutopilotMode.valueOf(config.getMode());
        } catch (Exception e) {
            mode = base.mode();
        }
        return new DeveloperProfile(base.applicationName(), base.goal(), base.maxCostPerRequest(),
                base.maxLatencyMs(), base.allowedProviders(), base.preferredModelClasses(), mode);
    }

    // ---- closed loop: observe → propose → verify → (canary) ----

    @Transactional
    public void runLoopFor(String developerId) {
        AutopilotConfigEntity config = configRepo.findById(developerId).orElse(null);
        if (config == null || !config.isEnabled() || config.getActiveBundleId() == null) {
            return;
        }
        // 1) Evaluate any in-flight canary first (promote/rollback/continue).
        evaluateCanaries(developerId, config);

        // Re-read (canary may have changed active/canary pointers).
        config = configRepo.findById(developerId).orElseThrow();
        if (config.getCanaryBundleId() != null) {
            return; // a canary is in progress; don't stack proposals
        }

        // 2) Observe telemetry.
        TelemetrySnapshot snapshot = aggregator.snapshot(developerId);
        telemetry.save(new AutopilotTelemetryEntity(developerId, json.write(snapshot)));
        if (snapshot.totalRequests() < 10) {
            record(developerId, "OBSERVE", "Gathering data (" + snapshot.totalRequests() + " requests)", null,
                    config.getActiveBundleId(), 0.0);
            return; // not enough signal yet
        }

        // 3) Propose.
        PolicyBundle active = bundles.bundle(config.getActiveBundleId()).orElseThrow();
        DeveloperProfile profile = profileOf(config);
        DecisionEngine.Proposal proposal = decisionEngine.propose(active, snapshot, profile);
        if (!proposal.changed()) {
            record(developerId, "SKIP", "Current policy remains optimal. " + proposal.rationale(), null,
                    config.getActiveBundleId(), proposal.confidence());
            return;
        }

        // 4) Verify before it can touch traffic.
        PolicyVerifier.VerificationResult vr = verifier.verify(proposal.candidate(), profile);
        if (!vr.passed()) {
            record(developerId, "REJECTED", "Candidate failed verification: " + vr.failures(), null,
                    config.getActiveBundleId(), proposal.confidence());
            return;
        }

        PolicyBundleEntity candidate = bundles.create(developerId, proposal.candidate(), PolicyStatus.CANDIDATE,
                config.getActiveBundleId(), "AUTOPILOT", proposal.rationale());
        recommendations.save(new AutopilotRecommendationEntity(developerId,
                "Proposed policy update", proposal.rationale(), summarizeImpact(proposal),
                candidate.getId(), proposal.confidence()));
        record(developerId, "PROPOSE", proposal.rationale(), detail(proposal), candidate.getId(), proposal.confidence());

        // 5) If the developer opted into autonomy, start a canary immediately.
        if (config.isAutoApply()) {
            startCanary(developerId, candidate.getId());
        }
    }

    // ---- recommendations ----

    @Transactional
    public void acceptRecommendation(String developerId, Long recId) {
        AutopilotRecommendationEntity rec = ownedRec(developerId, recId);
        rec.setStatus("ACCEPTED");
        recommendations.save(rec);
        if (rec.getProposedBundleId() != null) {
            startCanary(developerId, rec.getProposedBundleId());
        }
    }

    @Transactional
    public void rejectRecommendation(String developerId, Long recId) {
        AutopilotRecommendationEntity rec = ownedRec(developerId, recId);
        rec.setStatus("REJECTED");
        recommendations.save(rec);
        if (rec.getProposedBundleId() != null) {
            bundles.setStatus(rec.getProposedBundleId(), PolicyStatus.ARCHIVED);
        }
        record(developerId, "REJECT", "Developer rejected: " + rec.getTitle(), null, rec.getProposedBundleId(), 1.0);
    }

    // ---- canary ----

    @Transactional
    public AutopilotCanaryRunEntity startCanary(String developerId, Long candidateBundleId) {
        // V5 God Mode digital-twin pre-flight (additive, gated): a candidate that
        // confidently regresses in offline replay never receives live traffic.
        // No preflight bean / God Mode off / non-veto ⇒ exact V4 path below.
        CanaryPreflight preflight = canaryPreflight == null ? null : canaryPreflight.getIfAvailable();
        if (preflight != null) {
            CanaryPreflight.Result gate = preflight.check(developerId, candidateBundleId);
            if (gate.veto()) {
                bundles.setStatus(candidateBundleId, PolicyStatus.ARCHIVED);
                record(developerId, "TWIN_VETO", "Canary blocked before live traffic: " + gate.reason(),
                        null, candidateBundleId, gate.confidence());
                log.warn("Canary for {} bundle {} vetoed by digital twin: {}",
                        developerId, candidateBundleId, gate.reason());
                return null;
            }
        }
        AutopilotConfigEntity config = getOrCreateConfig(developerId);
        PolicyBundleEntity candidate = bundles.entity(candidateBundleId).orElseThrow();
        candidate.setStatus(PolicyStatus.CANARY);
        bundles.setStatus(candidateBundleId, PolicyStatus.CANARY);
        config.setCanaryBundleId(candidateBundleId);
        configRepo.save(config);
        PolicyBundle cb = bundles.parse(candidate);
        AutopilotCanaryRunEntity run = canaries.save(new AutopilotCanaryRunEntity(
                developerId, candidateBundleId, config.getActiveBundleId(), cb.canaryPercentage()));
        record(developerId, "CANARY_START", "Started canary at " + cb.canaryPercentage() + "%",
                null, candidateBundleId, 0.6);
        return run;
    }

    private void evaluateCanaries(String developerId, AutopilotConfigEntity config) {
        for (AutopilotCanaryRunEntity run : canaries.findByDeveloperIdAndStatus(developerId, "RUNNING")) {
            CanaryEvaluator.BundleStats cand = statsFor(run.getCandidateBundleId(), run.getStartedAt());
            CanaryEvaluator.BundleStats base = statsFor(run.getBaselineBundleId(), run.getStartedAt());
            CanaryEvaluator.Result result = canaryEvaluator.evaluate(cand, base);
            run.setMetricsJson(json.write(Map.of("candidate", cand, "baseline", base,
                    "verdict", result.verdict().name(), "reason", result.reason())));
            switch (result.verdict()) {
                case PROMOTE -> promote(developerId, config, run, result);
                case ROLLBACK -> rollback(developerId, config, run, result.reason(), true);
                case CONTINUE -> canaries.save(run);
            }
        }
    }

    private void promote(String developerId, AutopilotConfigEntity config, AutopilotCanaryRunEntity run,
                         CanaryEvaluator.Result result) {
        Long oldActive = config.getActiveBundleId();
        if (oldActive != null) {
            bundles.setStatus(oldActive, PolicyStatus.ARCHIVED);
        }
        bundles.setStatus(run.getCandidateBundleId(), PolicyStatus.ACTIVE);
        config.setActiveBundleId(run.getCandidateBundleId());
        config.setCanaryBundleId(null);
        configRepo.save(config);
        run.setStatus("PROMOTED");
        run.setEndedAt(Instant.now());
        canaries.save(run);
        record(developerId, "PROMOTE", "Promoted canary to active: " + result.reason(),
                null, run.getCandidateBundleId(), result.confidence());
    }

    private void rollback(String developerId, AutopilotConfigEntity config, AutopilotCanaryRunEntity run,
                          String reason, boolean automatic) {
        bundles.setStatus(run.getCandidateBundleId(), PolicyStatus.ROLLED_BACK);
        config.setCanaryBundleId(null);
        configRepo.save(config);
        run.setStatus("ROLLED_BACK");
        run.setEndedAt(Instant.now());
        canaries.save(run);
        rollbacks.save(new AutopilotRollbackEntity(developerId, run.getCandidateBundleId(),
                config.getActiveBundleId(), reason, automatic));
        record(developerId, "ROLLBACK", "Rolled back canary: " + reason, null, config.getActiveBundleId(), 0.9);
        log.warn("Autopilot rollback for {}: {}", developerId, reason);
    }

    /** Manual rollback: revert the active policy to the immediately previous version. */
    @Transactional
    public void manualRollback(String developerId) {
        AutopilotConfigEntity config = getOrCreateConfig(developerId);
        List<PolicyBundleEntity> history = bundles.history(developerId);
        Long current = config.getActiveBundleId();
        PolicyBundleEntity previous = history.stream()
                .filter(b -> b.getStatus() == PolicyStatus.ARCHIVED && !b.getId().equals(current))
                .findFirst().orElse(null);
        if (previous == null) {
            return;
        }
        if (current != null) {
            bundles.setStatus(current, PolicyStatus.ARCHIVED);
        }
        bundles.setStatus(previous.getId(), PolicyStatus.ACTIVE);
        config.setActiveBundleId(previous.getId());
        config.setCanaryBundleId(null);
        configRepo.save(config);
        rollbacks.save(new AutopilotRollbackEntity(developerId, current, previous.getId(),
                "Manual rollback to previous policy", false));
        record(developerId, "ROLLBACK", "Manual rollback to v" + previous.getVersion(), null, previous.getId(), 1.0);
    }

    // ---- feedback ----

    @Transactional
    public void addFeedback(String developerId, String requestRef, double score, String comment) {
        feedback.save(new AutopilotFeedbackEntity(developerId, requestRef, Math.max(-1, Math.min(1, score)), comment));
    }

    // ---- reads ----

    @Transactional(readOnly = true)
    public List<PolicyBundleEntity> history(String developerId) {
        return bundles.history(developerId);
    }

    @Transactional(readOnly = true)
    public List<AutopilotRecommendationEntity> recommendations(String developerId) {
        return recommendations.findByDeveloperIdOrderByCreatedAtDesc(developerId);
    }

    @Transactional(readOnly = true)
    public List<AutopilotCanaryRunEntity> canaryRuns(String developerId) {
        return canaries.findByDeveloperIdOrderByStartedAtDesc(developerId);
    }

    @Transactional(readOnly = true)
    public List<AutopilotRollbackEntity> rollbackHistory(String developerId) {
        return rollbacks.findByDeveloperIdOrderByCreatedAtDesc(developerId);
    }

    @Transactional(readOnly = true)
    public List<AutopilotDecisionEntity> decisionLog(String developerId, int limit) {
        return decisions.findByDeveloperIdOrderByCreatedAtDesc(developerId,
                org.springframework.data.domain.PageRequest.of(0, limit)).getContent();
    }

    public TelemetrySnapshot snapshot(String developerId) {
        return aggregator.snapshot(developerId);
    }

    // ---- helpers ----

    private CanaryEvaluator.BundleStats statsFor(Long bundleId, Instant since) {
        if (bundleId == null) {
            return new CanaryEvaluator.BundleStats(0, 0, 0, 0);
        }
        long succ = 0, fail = 0;
        double latSum = 0, costSum = 0;
        long n = 0;
        for (AutopilotRequestLabelEntity l : labels.findByBundleIdAndCreatedAtAfter(bundleId, since)) {
            if (l.isSuccess()) succ++; else fail++;
            latSum += l.getLatencyMs();
            costSum += l.getCostUsd();
            n++;
        }
        return new CanaryEvaluator.BundleStats(succ, fail, n == 0 ? 0 : latSum / n, n == 0 ? 0 : costSum / n);
    }

    private void record(String developerId, String type, String summary, String detailJson,
                        Long bundleId, double confidence) {
        decisions.save(new AutopilotDecisionEntity(developerId, type, summary, detailJson, bundleId, confidence));
    }

    private String detail(DecisionEngine.Proposal p) {
        return json.write(Map.of("routingMode", p.candidate().routingMode(),
                "providerOrder", p.candidate().providerOrder(),
                "hedgeThresholdMs", p.candidate().hedgeThresholdMs(),
                "confidence", p.confidence()));
    }

    private String summarizeImpact(DecisionEngine.Proposal p) {
        return "New provider order " + p.candidate().providerOrder()
                + ", hedge " + p.candidate().hedgeThresholdMs() + "ms";
    }

    private AutopilotRecommendationEntity ownedRec(String developerId, Long recId) {
        AutopilotRecommendationEntity rec = recommendations.findById(recId)
                .orElseThrow(() -> new IllegalArgumentException("No such recommendation"));
        if (!rec.getDeveloperId().equals(developerId)) {
            throw new IllegalArgumentException("Recommendation does not belong to this developer");
        }
        return rec;
    }
}
