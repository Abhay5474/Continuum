package io.continuum.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.persistence.entity.QualityGateCheckEntity;
import io.continuum.persistence.entity.QualityGateSettingEntity;
import io.continuum.persistence.repository.QualityGateCheckRepository;
import io.continuum.persistence.repository.QualityGateSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the quality gate's configuration, its records, and the evidence for
 * whether enforcing it is a good idea.
 *
 * <p>The interesting mode is {@code MONITOR}. Judging answers inline is the
 * feature on this roadmap most likely to disappoint — judges are biased, and
 * unaided self-correction is documented as sometimes making reasoning worse — so
 * shipping a gate that silently rewrites production traffic on day one would be
 * indefensible. In MONITOR the gate runs, records exactly what it <em>would</em>
 * have done, and changes nothing. The decision to enforce is then made against
 * your own traffic.
 *
 * <p>When enforcing, two limits are hard. A repair gets at most one attempt, and
 * it gets a latency budget after which the original answer is returned unchanged.
 * A slow correct answer is worse than a fast flawed one for anything interactive,
 * and a gate that loops can spend without bound on an answer it will never like.
 */
@Service
public class QualityGateService {

    private static final Logger log = LoggerFactory.getLogger(QualityGateService.class);

    private final QualityGateSettingRepository settings;
    private final QualityGateCheckRepository checks;
    private final ObjectMapper mapper;

    public QualityGateService(QualityGateSettingRepository settings, QualityGateCheckRepository checks,
                              ObjectMapper mapper) {
        this.settings = settings;
        this.checks = checks;
        this.mapper = mapper;
    }

    /** What the gate did, ready to return to the caller. */
    public record Outcome(String answer, QualityGate.Verdict verdict, boolean repaired,
                          double extraCost, long extraMs) {
    }

    @Transactional(readOnly = true)
    public QualityGateSettingEntity settingsFor(String developerId) {
        return settings.findById(developerId).orElseGet(() -> new QualityGateSettingEntity(developerId));
    }

    @Transactional(readOnly = true)
    public boolean activeFor(String developerId) {
        return developerId != null && settingsFor(developerId).getMode() != QualityGateSettingEntity.Mode.OFF;
    }

    /** Whether a failing verdict should actually change the answer. */
    @Transactional(readOnly = true)
    public boolean enforcing(String developerId) {
        return settingsFor(developerId).getMode() == QualityGateSettingEntity.Mode.ENFORCE;
    }

    /** Records a check. Never throws — telemetry must not fail a request. */
    @Transactional
    public void record(String developerId, QualityGateSettingEntity cfg, QualityGate.Verdict verdict,
                       String applied, String model, String original, String repaired,
                       QualityGate.Verdict afterRepair, double extraCost, long extraMs) {
        try {
            Boolean improved = null;
            Double repairScore = null;
            if (afterRepair != null) {
                repairScore = afterRepair.score();
                improved = afterRepair.score() > verdict.score();
            }
            checks.save(new QualityGateCheckEntity(developerId, cfg.getMode().name(), verdict.score(),
                    cfg.getThreshold(), verdict.action().name(), applied,
                    verdict.defects().isEmpty() ? null : String.join("; ", verdict.defects()),
                    mapper.writeValueAsString(QualityGate.scores(verdict)), model,
                    // Only kept when a repair ran, so the before/after is
                    // inspectable without storing every answer forever.
                    repaired == null ? null : truncate(original),
                    truncate(repaired), improved, repairScore, extraCost, extraMs));
        } catch (Exception e) {
            log.debug("Could not record quality check: {}", e.getMessage());
        }
    }

    // --- configuration -------------------------------------------------------

    @Transactional
    public Map<String, Object> configure(String developerId, String mode, Double threshold,
                                         Integer maxRepairs, Integer budgetMs,
                                         Boolean repairEngineEnabled) {
        QualityGateSettingEntity cfg = settingsFor(developerId);
        if (repairEngineEnabled != null) {
            cfg.setRepairEngineEnabled(repairEngineEnabled);
        }
        if (mode != null) {
            try {
                cfg.setMode(QualityGateSettingEntity.Mode.valueOf(mode.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown mode: " + mode);
            }
        }
        if (threshold != null) {
            cfg.setThreshold(threshold);
        }
        if (maxRepairs != null) {
            cfg.setMaxRepairs(maxRepairs);
        }
        if (budgetMs != null) {
            cfg.setBudgetMs(budgetMs);
        }
        settings.save(cfg);
        return status(developerId);
    }

    @Transactional
    public Map<String, Object> clear(String developerId) {
        checks.deleteByDeveloperId(developerId);
        return status(developerId);
    }

    /**
     * The gate's own report card.
     *
     * <p>Reports repair <em>effectiveness</em> separately from repair count,
     * because a gate that fires often and fixes nothing is worse than no gate:
     * it costs a second call and returns the same defect.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> status(String developerId) {
        QualityGateSettingEntity cfg = settingsFor(developerId);
        List<QualityGateCheckEntity> rows = checks.recentFor(developerId, PageRequest.of(0, 1000));

        long total = rows.size();
        long wouldAct = rows.stream().filter(r -> !"PASS".equals(r.getAction())).count();
        long repaired = rows.stream().filter(r -> "REPAIR".equals(r.getApplied())).count();
        long blocked = rows.stream().filter(r -> "BLOCK".equals(r.getAction())).count();

        List<QualityGateCheckEntity> withRepair = rows.stream()
                .filter(r -> r.getRepairImproved() != null).toList();
        long improved = withRepair.stream()
                .filter(r -> Boolean.TRUE.equals(r.getRepairImproved())).count();

        // Per-dimension failure counts: which check is actually doing the work,
        // and which is only adding noise.
        Map<String, Long> failures = new LinkedHashMap<>();
        Map<String, Double> means = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (QualityGateCheckEntity r : rows) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Number> dims = mapper.readValue(
                        r.getDimensionsJson() == null ? "{}" : r.getDimensionsJson(), Map.class);
                dims.forEach((k, v) -> {
                    double d = v.doubleValue();
                    means.merge(k, d, Double::sum);
                    counts.merge(k, 1L, Long::sum);
                    if (d < 1.0) {
                        failures.merge(k, 1L, Long::sum);
                    }
                });
            } catch (Exception ignored) {
                // A malformed row must not break the report.
            }
        }
        List<Map<String, Object>> dimensions = new ArrayList<>();
        for (String name : List.of("adherence", "completeness", "grounding", "relevance")) {
            Map<String, Object> d = new LinkedHashMap<>();
            long n = counts.getOrDefault(name, 0L);
            d.put("name", name);
            d.put("checked", n);
            d.put("failed", failures.getOrDefault(name, 0L));
            d.put("meanScore", n == 0 ? null : means.getOrDefault(name, 0.0) / n);
            dimensions.add(d);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", cfg.getMode().name());
        out.put("threshold", cfg.getThreshold());
        out.put("repairEngineEnabled", cfg.isRepairEngineEnabled());
        out.put("maxRepairs", cfg.getMaxRepairs());
        out.put("budgetMs", cfg.getBudgetMs());
        out.put("checked", total);
        out.put("wouldAct", wouldAct);
        out.put("wouldActRate", total == 0 ? 0.0 : (double) wouldAct / total);
        out.put("repaired", repaired);
        out.put("blocked", blocked);
        out.put("repairAttempts", withRepair.size());
        out.put("repairsImproved", improved);
        // The number that decides whether enforcing is worth it.
        out.put("repairSuccessRate", withRepair.isEmpty() ? null : (double) improved / withRepair.size());
        out.put("dimensions", dimensions);
        out.put("extraCost", rows.stream().mapToDouble(QualityGateCheckEntity::getExtraCost).sum());
        out.put("extraMs", rows.isEmpty() ? 0L
                : (long) rows.stream().mapToLong(QualityGateCheckEntity::getExtraMs).average().orElse(0));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recent(String developerId, int limit) {
        return checks.recentFor(developerId, PageRequest.of(0, Math.max(1, Math.min(100, limit))))
                .stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getId());
                    m.put("mode", r.getMode());
                    m.put("score", r.getScore());
                    m.put("threshold", r.getThreshold());
                    m.put("action", r.getAction());
                    m.put("applied", r.getApplied());
                    m.put("defects", r.getDefects());
                    m.put("model", r.getModel());
                    m.put("originalAnswer", r.getOriginalAnswer());
                    m.put("repairedAnswer", r.getRepairedAnswer());
                    m.put("repairImproved", r.getRepairImproved());
                    m.put("repairScore", r.getRepairScore());
                    m.put("extraCost", r.getExtraCost());
                    m.put("extraMs", r.getExtraMs());
                    m.put("createdAt", r.getCreatedAt());
                    try {
                        m.put("dimensions", mapper.readValue(
                                r.getDimensionsJson() == null ? "{}" : r.getDimensionsJson(), Map.class));
                    } catch (Exception e) {
                        m.put("dimensions", Map.of());
                    }
                    return m;
                }).toList();
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 2000 ? s : s.substring(0, 2000) + "…";
    }
}
