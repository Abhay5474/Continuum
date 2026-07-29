package io.continuum.cascade;

import io.continuum.persistence.entity.CascadeDecisionEntity;
import io.continuum.persistence.entity.CascadeSettingEntity;
import io.continuum.persistence.entity.ModelEntity;
import io.continuum.persistence.repository.CascadeDecisionRepository;
import io.continuum.persistence.repository.CascadeSettingRepository;
import io.continuum.registry.ModelCapabilities;
import io.continuum.registry.ModelRegistryService;
import io.continuum.semantic.TextVectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Verify-then-Escalate: answer with the cheap model, judge it, pay for the
 * expensive one only when the judge says so.
 *
 * <p>Continuum's existing failover chain escalates on <em>errors</em>. A cheap
 * model that returns a confident, fluent, wrong answer is a success as far as
 * that chain is concerned, so every request paid for whichever model the scorer
 * guessed it needed. FrugalGPT (Chen, Zaharia &amp; Zou, 2023) is the
 * counter-proposal: try cheap, check, upgrade — matching the strong model's
 * quality at a fraction of its cost.
 *
 * <p>Three design decisions are worth stating, because they are what separate a
 * cascade you can trust from one that quietly ships worse answers.
 *
 * <p><b>The label is free.</b> Whenever both tiers run, their answers are
 * compared. Agreement means the cheap answer was fine and the escalation was
 * wasted; disagreement means it was earned. No human, no benchmark, no ground
 * truth — and it is exactly the signal needed to calibrate the threshold.
 *
 * <p><b>There is an audit slice.</b> The label above only ever arrives for
 * requests that escalated, which measures false positives and is blind to false
 * negatives. A small sampled fraction of traffic runs both tiers regardless of
 * the verdict, so the escalations the judge <em>missed</em> are measurable too.
 * Without it a cascade looks like a triumph while degrading quietly.
 *
 * <p><b>Escalating costs more, not less.</b> A miscalibrated judge that
 * escalates everything makes the bill go up. The escalation rate is compared to
 * a configured cap and surfaced, so that failure is loud.
 */
@Service
public class ResponseCascadeService {

    private static final Logger log = LoggerFactory.getLogger(ResponseCascadeService.class);


    private final CascadeSettingRepository settings;
    private final CascadeDecisionRepository decisions;
    private final ModelRegistryService registry;
    private final io.continuum.provider.ProviderRouter router;
    private final DeferralJudge judge;
    private final CalibrationStore calibration;
    /**
     * Agreement between the two tiers was originally raw cosine, which reported
     * a terse answer and a verbose answer stating the same fact as
     * <em>disagreement</em> — biasing the audit toward over-reporting missed
     * escalations. The clusterer compares assertions first, so "30 days" and
     * "The refund window is thirty days from renewal" agree, while "30 days" and
     * "14 days" do not however similar the surrounding prose.
     */
    private final io.continuum.uncertainty.AnswerClusterer clusterer;

    public ResponseCascadeService(CascadeSettingRepository settings, CascadeDecisionRepository decisions,
                                  ModelRegistryService registry, io.continuum.provider.ProviderRouter router,
                                  DeferralJudge judge, CalibrationStore calibration,
                                  io.continuum.uncertainty.AnswerClusterer clusterer) {
        this.settings = settings;
        this.decisions = decisions;
        this.registry = registry;
        this.router = router;
        this.judge = judge;
        this.calibration = calibration;
        this.clusterer = clusterer;
    }

    /** A tier of the cascade: a concrete (provider, model) with its input price. */
    public record Tier(String provider, String model, double costPer1k, String label) {
    }

    @Transactional(readOnly = true)
    public boolean enabledFor(String developerId) {
        if (developerId == null) {
            return false;
        }
        return settings.findById(developerId).map(CascadeSettingEntity::isEnabled).orElse(false);
    }

    @Transactional(readOnly = true)
    public double thresholdFor(String developerId) {
        return settings.findById(developerId).map(CascadeSettingEntity::getThreshold).orElse(0.75);
    }

    /** Whether this request should run both tiers to measure a missed escalation. */
    public boolean shouldAudit(String developerId) {
        double rate = settings.findById(developerId).map(CascadeSettingEntity::getAuditRate).orElse(0.0);
        return rate > 0 && ThreadLocalRandom.current().nextDouble() < rate;
    }

    /**
     * The cascade's tiers, cheapest first.
     *
     * <p>Derived from the live model registry by input price rather than
     * configured by hand: a hand-written tier list goes stale the moment a
     * provider ships a new model, and the registry already knows what is active
     * and what it costs.
     */
    public List<Tier> tiers() {
        // Only providers the router can actually call. The registry lists every
        // model it knows about, including ones whose provider has no credential
        // configured; offering one of those as a tier would produce a cascade
        // that escalates into a guaranteed failure.
        java.util.Set<String> reachable = new java.util.HashSet<>(router.availableChain());
        List<Tier> out = new ArrayList<>();
        for (ModelEntity m : registry.active()) {
            if (!reachable.contains(m.getProvider())) {
                continue;
            }
            ModelCapabilities cap = registry.capabilitiesOf(m);
            out.add(new Tier(m.getProvider(), m.getModelName(),
                    cap == null ? 0 : cap.costInputPer1k(),
                    cap == null ? null : cap.tier()));
        }
        out.sort(Comparator.comparingDouble(Tier::costPer1k).thenComparing(Tier::model));
        return out;
    }

    /** The cheap tier and the strong tier, or empty when there is no real choice. */
    public List<Tier> cheapAndStrong() {
        List<Tier> all = tiers();
        if (all.size() < 2) {
            return List.of();
        }
        Tier cheap = all.get(0);
        Tier strong = all.get(all.size() - 1);
        // A cascade over two models with the same price saves nothing and costs
        // an extra call; decline rather than pretend.
        if (strong.costPer1k() <= cheap.costPer1k()) {
            return List.of();
        }
        return List.of(cheap, strong);
    }

    /** The verdict on a cheap answer, with the calibrated probability attached. */
    public record Assessment(double rawScore, double confidence, double threshold, boolean escalate,
                             String reason, List<String> concerns) {
    }

    /** Judges the cheap tier's answer and decides whether to escalate. */
    public Assessment assess(String developerId, io.continuum.provider.model.LlmRequest request,
                             String answer, double complexity) {
        DeferralJudge.Verdict v = judge.judge(request, answer, complexity);
        double confidence = calibration.calibrate(developerId, v.rawScore());
        double threshold = thresholdFor(developerId);
        boolean escalate = confidence < threshold;
        String reason = escalate
                ? String.format("confidence %.2f below threshold %.2f — %s", confidence, threshold, v.summary())
                : String.format("confidence %.2f — %s", confidence, v.summary());
        return new Assessment(v.rawScore(), confidence, threshold, escalate, reason, v.concerns());
    }

    /**
     * Records a decision and, when both answers exist, learns from their
     * agreement.
     *
     * <p>Never throws: a cascade is an optimisation, and losing a telemetry row
     * must not lose the request that produced it.
     */
    public void record(String developerId, Assessment a, boolean escalated, boolean audit,
                       String cheapAnswer, String strongAnswer,
                       String cheapModel, String strongModel,
                       double cheapCost, double strongCost,
                       long cheapLatencyMs, long totalLatencyMs, double complexity) {
        try {
            Boolean agreed = null;
            Double similarity = null;
            if (cheapAnswer != null && strongAnswer != null) {
                similarity = TextVectors.cosine(cheapAnswer, strongAnswer);
                agreed = clusterer.sameMeaning(cheapAnswer, strongAnswer);
                // The free label: "the cheap answer was sufficient" is exactly
                // "the strong model said the same thing".
                calibration.observe(developerId, a.rawScore(), agreed);
            }
            decisions.save(new CascadeDecisionEntity(developerId, complexity, a.rawScore(), a.confidence(),
                    a.threshold(), escalated, audit, agreed, similarity,
                    a.concerns().isEmpty() ? null : String.join("; ", a.concerns()),
                    cheapModel, strongModel, cheapCost, strongCost, cheapLatencyMs, totalLatencyMs));
        } catch (Exception e) {
            log.debug("Could not record cascade decision: {}", e.getMessage());
        }
    }

    // --- configuration -------------------------------------------------------

    @Transactional
    public Map<String, Object> setEnabled(String developerId, boolean enabled) {
        CascadeSettingEntity cfg = load(developerId);
        cfg.setEnabled(enabled);
        settings.save(cfg);
        return status(developerId);
    }

    @Transactional
    public Map<String, Object> configure(String developerId, Double threshold, Double auditRate,
                                         Double escalationCap) {
        CascadeSettingEntity cfg = load(developerId);
        if (threshold != null) {
            cfg.setThreshold(threshold);
        }
        if (auditRate != null) {
            cfg.setAuditRate(auditRate);
        }
        if (escalationCap != null) {
            cfg.setEscalationCap(escalationCap);
        }
        settings.save(cfg);
        return status(developerId);
    }

    @Transactional
    public Map<String, Object> reset(String developerId) {
        decisions.deleteByDeveloperId(developerId);
        calibration.reset(developerId);
        return status(developerId);
    }

    /**
     * Everything the console needs, and the numbers that decide whether the
     * cascade is earning its place.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> status(String developerId) {
        CascadeSettingEntity cfg = settings.findById(developerId)
                .orElseGet(() -> new CascadeSettingEntity(developerId));
        List<CascadeDecisionEntity> rows = decisions.recentFor(developerId, PageRequest.of(0, 1000));
        List<Tier> pair = cheapAndStrong();

        long total = rows.size();
        long escalated = rows.stream().filter(CascadeDecisionEntity::isEscalated).count();
        long audited = rows.stream().filter(CascadeDecisionEntity::isAudit).count();

        double spent = rows.stream().mapToDouble(r -> r.getCheapCost() + r.getStrongCost()).sum();
        // What the same traffic would have cost on the strong model alone. Where
        // a strong call actually happened its real price is known; elsewhere it
        // is priced from the tier table.
        double strongOnly = 0;
        for (CascadeDecisionEntity r : rows) {
            strongOnly += r.getStrongCost() > 0 ? r.getStrongCost() : estimateStrong(r, pair);
        }

        // Escalations where the strong model turned out to agree: money spent
        // for no change in the answer.
        List<CascadeDecisionEntity> withLabel = rows.stream()
                .filter(r -> r.getAgreedWithStrong() != null).toList();
        long wasted = withLabel.stream()
                .filter(r -> r.isEscalated() && Boolean.TRUE.equals(r.getAgreedWithStrong())).count();
        // Audit rows the judge would have accepted, where the strong model in
        // fact disagreed: the escalations it MISSED. The number that matters.
        List<CascadeDecisionEntity> auditRows = withLabel.stream()
                .filter(CascadeDecisionEntity::isAudit).toList();
        long missed = auditRows.stream()
                .filter(r -> !r.isEscalated() && Boolean.FALSE.equals(r.getAgreedWithStrong())).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.isEnabled());
        out.put("threshold", cfg.getThreshold());
        out.put("auditRate", cfg.getAuditRate());
        out.put("escalationCap", cfg.getEscalationCap());
        out.put("available", !pair.isEmpty());
        out.put("cheapTier", pair.isEmpty() ? null : describe(pair.get(0)));
        out.put("strongTier", pair.isEmpty() ? null : describe(pair.get(1)));
        out.put("requests", total);
        out.put("escalated", escalated);
        out.put("escalationRate", total == 0 ? 0.0 : (double) escalated / total);
        out.put("overCap", total > 0 && (double) escalated / total > cfg.getEscalationCap());
        out.put("audited", audited);
        out.put("spend", spent);
        out.put("strongOnlySpend", strongOnly);
        out.put("saved", Math.max(0, strongOnly - spent));
        out.put("savedPct", strongOnly <= 0 ? 0.0 : Math.max(0, (strongOnly - spent) / strongOnly));
        out.put("wastedEscalations", wasted);
        out.put("auditSamples", auditRows.size());
        out.put("missedEscalations", missed);
        out.put("missRate", auditRows.isEmpty() ? null : (double) missed / auditRows.size());
        out.put("calibration", calibration.profile(developerId));

        // Whether running the strong model speculatively — in parallel rather
        // than after — would be worth it, computed from the escalation rate just
        // measured above rather than from intuition.
        double strongCost = 0;
        double strongLatency = 0;
        int strongRuns = 0;
        for (CascadeDecisionEntity r : rows) {
            if (r.getStrongCost() > 0) {
                strongCost += r.getStrongCost();
                // The strong call's own latency is what the total cost beyond
                // the cheap one — which is precisely the wait speculation
                // removes, since under speculation it was already running.
                strongLatency += Math.max(0, r.getTotalLatencyMs() - r.getCheapLatencyMs());
                strongRuns++;
            }
        }
        out.put("speculation", SpeculationEconomics.advise(total, escalated,
                strongRuns == 0 ? 0 : strongCost / strongRuns,
                strongRuns == 0 ? 0 : strongLatency / strongRuns,
                spent).describe());
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recent(String developerId, int limit) {
        return decisions.recentFor(developerId, PageRequest.of(0, Math.max(1, Math.min(200, limit))))
                .stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getId());
                    m.put("complexity", r.getComplexity());
                    m.put("rawScore", r.getRawScore());
                    m.put("confidence", r.getConfidence());
                    m.put("threshold", r.getThreshold());
                    m.put("escalated", r.isEscalated());
                    m.put("audit", r.isAudit());
                    m.put("agreedWithStrong", r.getAgreedWithStrong());
                    m.put("similarity", r.getSimilarity());
                    m.put("concerns", r.getConcerns());
                    m.put("cheapModel", r.getCheapModel());
                    m.put("strongModel", r.getStrongModel());
                    m.put("cost", r.getCheapCost() + r.getStrongCost());
                    m.put("cheapLatencyMs", r.getCheapLatencyMs());
                    m.put("totalLatencyMs", r.getTotalLatencyMs());
                    m.put("createdAt", r.getCreatedAt());
                    return m;
                }).toList();
    }

    private CascadeSettingEntity load(String developerId) {
        return settings.findById(developerId).orElseGet(() -> new CascadeSettingEntity(developerId));
    }

    /**
     * Prices a request on the strong tier when it never actually ran there.
     * Scaled from the cheap call's real cost by the tier price ratio, which is
     * an estimate and is labelled as one in the console.
     */
    private static double estimateStrong(CascadeDecisionEntity r, List<Tier> pair) {
        if (pair.isEmpty() || pair.get(0).costPer1k() <= 0) {
            return r.getCheapCost();
        }
        double ratio = pair.get(1).costPer1k() / pair.get(0).costPer1k();
        return r.getCheapCost() * ratio;
    }

    private static Map<String, Object> describe(Tier t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", t.provider());
        m.put("model", t.model());
        m.put("costPer1k", t.costPer1k());
        m.put("label", t.label());
        return m;
    }
}
