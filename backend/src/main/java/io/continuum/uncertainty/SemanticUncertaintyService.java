package io.continuum.uncertainty;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.persistence.entity.UncertaintyMeasurementEntity;
import io.continuum.persistence.entity.UncertaintySettingEntity;
import io.continuum.persistence.repository.UncertaintyMeasurementRepository;
import io.continuum.persistence.repository.UncertaintySettingRepository;
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
 * How much the model actually agrees with itself.
 *
 * <p>Continuum returns an answer with no indication of whether the model was
 * confident or confabulating, so every calling application has to treat all
 * answers as equally reliable — which is why chat interfaces render
 * hallucinations with the same typographic confidence as facts.
 *
 * <p>Farquhar, Kossen, Kuhn &amp; Gal (<i>Detecting hallucinations in large
 * language models using semantic entropy</i>, Nature 2024) give the method:
 * sample the same question several times, cluster the answers by <em>meaning</em>
 * rather than by wording, and take entropy over the meaning classes. A model
 * that says "thirty days" five different ways is certain; one that gives five
 * different durations is making it up. Token-level probabilities cannot separate
 * those two cases, because they measure surface form.
 *
 * <p>Entropy is normalised by {@code log(k)} so the number means the same thing
 * at three samples and at seven — otherwise raising the sample count would look
 * like rising uncertainty.
 *
 * <p>The measurement costs k times the tokens, which is why {@code Mode} exists.
 * {@code ADAPTIVE} is the interesting setting: measure only where the cascade's
 * cheap judge was already unsure, so the multiplier applies to a slice rather
 * than to everything.
 */
@Service
public class SemanticUncertaintyService {

    private static final Logger log = LoggerFactory.getLogger(SemanticUncertaintyService.class);

    private final UncertaintySettingRepository settings;
    private final UncertaintyMeasurementRepository measurements;
    private final AnswerClusterer clusterer;
    private final ObjectMapper mapper;

    public SemanticUncertaintyService(UncertaintySettingRepository settings,
                                      UncertaintyMeasurementRepository measurements,
                                      AnswerClusterer clusterer, ObjectMapper mapper) {
        this.settings = settings;
        this.measurements = measurements;
        this.clusterer = clusterer;
        this.mapper = mapper;
    }

    /** A measured answer: the confidence, and the disagreement behind it. */
    public record Measurement(double entropy, double normalised, double confidence, int samples,
                              int clusters, List<ClusterView> breakdown, boolean lowConfidence) {
    }

    /** One meaning class, for display and for the API. */
    public record ClusterView(int size, double share, String representative) {
    }

    @Transactional(readOnly = true)
    public UncertaintySettingEntity settingsFor(String developerId) {
        return settings.findById(developerId).orElseGet(() -> new UncertaintySettingEntity(developerId));
    }

    /**
     * Whether to measure this request.
     *
     * @param requested   the caller asked for it explicitly
     * @param judgeUnsure the cascade judge was in its ambivalent band
     */
    @Transactional(readOnly = true)
    public boolean shouldMeasure(String developerId, boolean requested, boolean judgeUnsure) {
        if (developerId == null) {
            return false;
        }
        return switch (settingsFor(developerId).getMode()) {
            case OFF -> false;
            case ON_DEMAND -> requested;
            case ADAPTIVE -> requested || judgeUnsure;
            case ALWAYS -> true;
        };
    }

    /**
     * How many answers fell into each meaning-cluster.
     *
     * <p>Exposed so the adaptive stopping rule can ask "is this decided yet?"
     * after each sample, using exactly the clustering the final measurement will
     * use. Deciding to stop on a different notion of agreement than the one that
     * produces the answer would be measuring one thing and acting on another.
     */
    public List<Integer> clusterSizes(List<String> answers) {
        if (answers == null || answers.size() < 2) {
            return List.of();
        }
        return clusterer.cluster(answers).stream().map(AnswerClusterer.Cluster::size).toList();
    }


    /**
     * Computes semantic entropy over a set of sampled answers.
     *
     * <p>Pure: the sampling itself happens at the gateway, which owns the
     * provider calls. This keeps the maths testable without a model.
     */
    public Measurement measure(String developerId, List<String> answers) {
        int k = answers == null ? 0 : answers.size();
        if (k < 2) {
            // One sample cannot disagree with itself. Reporting confidence 1.0
            // here would be a lie of exactly the kind this feature exists to
            // prevent, so it reports nothing measurable instead.
            return new Measurement(0, 0, Double.NaN, k, k, List.of(), false);
        }
        List<AnswerClusterer.Cluster> clusters = clusterer.cluster(answers);

        double entropy = 0;
        List<ClusterView> views = new ArrayList<>();
        for (AnswerClusterer.Cluster c : clusters) {
            double p = (double) c.size() / k;
            entropy -= p * Math.log(p);
            views.add(new ClusterView(c.size(), p, truncate(c.representative())));
        }
        // log(k) is the entropy of maximal disagreement — every sample its own
        // meaning — so dividing by it puts different sample counts on one scale.
        double maxEntropy = Math.log(k);
        double normalised = maxEntropy <= 0 ? 0 : entropy / maxEntropy;
        double confidence = 1.0 - normalised;

        double lowBar = settingsFor(developerId).getLowConfidence();
        return new Measurement(entropy, normalised, confidence, k, clusters.size(), views,
                confidence < lowBar);
    }

    /** Persists a measurement. Never throws — telemetry must not fail a request. */
    @Transactional
    public void record(String developerId, String prompt, String model, Measurement m,
                       double extraCost, long extraMs) {
        try {
            measurements.save(new UncertaintyMeasurementEntity(developerId, truncate(prompt), model,
                    m.samples(), m.clusters(), m.entropy(), m.normalised(),
                    Double.isNaN(m.confidence()) ? 0 : m.confidence(),
                    mapper.writeValueAsString(m.breakdown()), extraCost, extraMs));
        } catch (Exception e) {
            log.debug("Could not record uncertainty measurement: {}", e.getMessage());
        }
    }

    // --- configuration -------------------------------------------------------

    @Transactional
    /**
     * The pre-adaptive signature, kept so existing callers and tests are not
     * churned by a feature they do not use. Delegates with the adaptive fields
     * left alone.
     */
    public Map<String, Object> configure(String developerId, String mode, Integer samples,
                                         Double temperature, Double lowConfidence) {
        return configure(developerId, mode, samples, null, null, temperature, lowConfidence);
    }

    public Map<String, Object> configure(String developerId, String mode, Integer samples,
                                         Boolean adaptiveEnabled, Double overturnThreshold,
                                         Double temperature, Double lowConfidence) {
        UncertaintySettingEntity cfg = settingsFor(developerId);
        if (mode != null) {
            try {
                cfg.setMode(UncertaintySettingEntity.Mode.valueOf(mode.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown mode: " + mode);
            }
        }
        if (adaptiveEnabled != null) {
            cfg.setAdaptiveEnabled(adaptiveEnabled);
        }
        if (overturnThreshold != null) {
            cfg.setOverturnThreshold(overturnThreshold);
        }
        if (samples != null) {
            cfg.setSamples(samples);
        }
        if (temperature != null) {
            cfg.setTemperature(temperature);
        }
        if (lowConfidence != null) {
            cfg.setLowConfidence(lowConfidence);
        }
        settings.save(cfg);
        return status(developerId);
    }

    @Transactional
    public Map<String, Object> clear(String developerId) {
        measurements.deleteByDeveloperId(developerId);
        return status(developerId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(String developerId) {
        UncertaintySettingEntity cfg = settingsFor(developerId);
        List<UncertaintyMeasurementEntity> rows =
                measurements.recentFor(developerId, PageRequest.of(0, 500));

        long lowCount = rows.stream().filter(r -> r.getConfidence() < cfg.getLowConfidence()).count();
        double avgConfidence = rows.isEmpty() ? 0
                : rows.stream().mapToDouble(UncertaintyMeasurementEntity::getConfidence).average().orElse(0);

        // Distribution over ten buckets, so the console can draw the shape
        // rather than a single average that hides a bimodal workload.
        long[] histogram = new long[10];
        for (UncertaintyMeasurementEntity r : rows) {
            int b = (int) Math.max(0, Math.min(9, Math.floor(r.getConfidence() * 10)));
            histogram[b]++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", cfg.getMode().name());
        out.put("samples", cfg.getSamples());
        out.put("adaptiveEnabled", cfg.isAdaptiveEnabled());
        out.put("overturnThreshold", cfg.getOverturnThreshold());
        out.put("temperature", cfg.getTemperature());
        out.put("lowConfidence", cfg.getLowConfidence());
        out.put("measured", rows.size());
        out.put("lowConfidenceCount", lowCount);
        out.put("lowConfidenceRate", rows.isEmpty() ? 0.0 : (double) lowCount / rows.size());
        out.put("avgConfidence", avgConfidence);
        out.put("extraCost", rows.stream().mapToDouble(UncertaintyMeasurementEntity::getExtraCost).sum());
        out.put("extraMs", rows.isEmpty() ? 0
                : (long) rows.stream().mapToLong(UncertaintyMeasurementEntity::getExtraMs).average().orElse(0));
        List<Map<String, Object>> hist = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("from", i / 10.0);
            h.put("to", (i + 1) / 10.0);
            h.put("count", histogram[i]);
            hist.add(h);
        }
        out.put("histogram", hist);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recent(String developerId, int limit) {
        return measurements.recentFor(developerId, PageRequest.of(0, Math.max(1, Math.min(100, limit))))
                .stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getId());
                    m.put("prompt", r.getPrompt());
                    m.put("model", r.getModel());
                    m.put("samples", r.getSamples());
                    m.put("clusters", r.getClusters());
                    m.put("entropy", r.getEntropy());
                    m.put("confidence", r.getConfidence());
                    m.put("extraCost", r.getExtraCost());
                    m.put("extraMs", r.getExtraMs());
                    m.put("createdAt", r.getCreatedAt());
                    try {
                        m.put("breakdown", mapper.readValue(
                                r.getClustersJson() == null ? "[]" : r.getClustersJson(), List.class));
                    } catch (Exception e) {
                        m.put("breakdown", List.of());
                    }
                    return m;
                }).toList();
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }
}
