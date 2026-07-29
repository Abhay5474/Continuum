package io.continuum.counterfactual;

import io.continuum.persistence.entity.CounterfactualSettingEntity;
import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.repository.CounterfactualSettingRepository;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Replays logged traffic against a candidate routing policy. */
@Service
public class CounterfactualService {

    /** Most requests one evaluation will read. Beyond this the answer stops changing. */
    private static final int MAX_SAMPLE = 5000;

    private final CounterfactualSettingRepository settings;
    private final GatewayRequestLogRepository log;

    public CounterfactualService(CounterfactualSettingRepository settings,
                                 GatewayRequestLogRepository log) {
        this.settings = settings;
        this.log = log;
    }

    @Transactional(readOnly = true)
    public boolean enabled(String developerId) {
        return developerId != null && settings.findById(developerId)
                .map(CounterfactualSettingEntity::isEnabled).orElse(false);
    }

    @Transactional
    public Map<String, Object> configure(String developerId, Boolean enabled) {
        CounterfactualSettingEntity cfg = settings.findById(developerId)
                .orElseGet(() -> new CounterfactualSettingEntity(developerId));
        if (enabled != null) {
            cfg.setEnabled(enabled);
        }
        settings.save(cfg);
        return status(developerId);
    }

    /** What can be replayed, and against which arms. */
    @Transactional(readOnly = true)
    public Map<String, Object> status(String developerId) {
        List<CounterfactualEvaluator.Observation> obs = sample(developerId, MAX_SAMPLE);

        Map<String, Integer> armCounts = new LinkedHashMap<>();
        double spent = 0;
        for (CounterfactualEvaluator.Observation o : obs) {
            armCounts.merge(o.provider() + "/" + o.model(), 1, Integer::sum);
            spent += o.costUsd();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled(developerId));
        out.put("requestsAvailable", obs.size());
        out.put("maxSample", MAX_SAMPLE);
        out.put("actualSpend", spent);
        List<Map<String, Object>> arms = new ArrayList<>();
        armCounts.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("arm", k);
            m.put("requests", v);
            arms.add(m);
        });
        out.put("arms", arms);
        return out;
    }

    /**
     * Replays the log against a candidate.
     *
     * @param alwaysArm    evaluate "always this arm", or null
     * @param at           cascade threshold, used with {@code below}/{@code above}
     */
    @Transactional(readOnly = true)
    public Map<String, Object> evaluate(String developerId, String alwaysArm,
                                        Double at, String below, String above, Integer limit) {
        if (!enabled(developerId)) {
            return Map.of("enabled", false,
                    "message", "Counterfactual evaluation is off for this account.");
        }
        CounterfactualEvaluator.Policy policy = alwaysArm != null && !alwaysArm.isBlank()
                ? CounterfactualEvaluator.always(alwaysArm.strip())
                : CounterfactualEvaluator.threshold(
                        at == null ? 0.5 : at,
                        below == null ? "" : below.strip(),
                        above == null ? "" : above.strip());

        List<CounterfactualEvaluator.Observation> obs =
                sample(developerId, limit == null ? MAX_SAMPLE : Math.min(limit, MAX_SAMPLE));

        Map<String, Object> out = new LinkedHashMap<>(
                CounterfactualEvaluator.evaluate(obs, policy).describe());
        out.put("enabled", true);
        return out;
    }

    /** The arms that appear in this tenant's log, so the UI can offer real choices. */
    @Transactional(readOnly = true)
    public Set<String> arms(String developerId) {
        Set<String> out = new LinkedHashSet<>();
        for (CounterfactualEvaluator.Observation o : sample(developerId, MAX_SAMPLE)) {
            out.add(o.provider() + "/" + o.model());
        }
        return out;
    }

    private List<CounterfactualEvaluator.Observation> sample(String developerId, int limit) {
        List<CounterfactualEvaluator.Observation> out = new ArrayList<>();
        for (GatewayRequestLogEntity r : log.findByDeveloperIdOrderByCreatedAtDesc(
                developerId, PageRequest.of(0, Math.max(1, limit)))) {
            // A cache hit cost nothing and went to no provider; replaying it
            // against a routing policy would credit the candidate with a saving
            // that had nothing to do with routing.
            if (r.getChosenProvider() == null || r.getTokens() <= 0) {
                continue;
            }
            out.add(new CounterfactualEvaluator.Observation(
                    r.getChosenProvider(), r.getChosenModel(), r.getComplexity(),
                    r.getLatencyMs(), r.getTokens(), r.getCostUsd(), r.isSuccess()));
        }
        return out;
    }
}
