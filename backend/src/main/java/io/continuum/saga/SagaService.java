package io.continuum.saga;

import io.continuum.persistence.entity.SagaEventEntity;
import io.continuum.persistence.entity.SagaSettingEntity;
import io.continuum.persistence.repository.SagaEventRepository;
import io.continuum.persistence.repository.SagaSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-tenant saga compensation, and the record of every rollback it ran. */
@Service
public class SagaService {

    private static final Logger log = LoggerFactory.getLogger(SagaService.class);

    private final SagaSettingRepository settings;
    private final SagaEventRepository events;

    public SagaService(SagaSettingRepository settings, SagaEventRepository events) {
        this.settings = settings;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public boolean enabled(String developerId) {
        return developerId != null
                && settings.findById(developerId).map(SagaSettingEntity::isEnabled).orElse(false);
    }

    @Transactional
    public Map<String, Object> configure(String developerId, Boolean enabled) {
        SagaSettingEntity cfg = settings.findById(developerId)
                .orElseGet(() -> new SagaSettingEntity(developerId));
        if (enabled != null) {
            cfg.setEnabled(enabled);
        }
        settings.save(cfg);
        return status(developerId);
    }

    /**
     * Records a rollback.
     *
     * <p>Called from an activity, so it runs once and replays thereafter. A
     * failure to write the log must not fail the rollback itself — the
     * compensations matter more than the record of them.
     */
    @Transactional
    public void record(String developerId, String workflowId, String definition, String failedStep,
                       SagaPlan.Plan plan) {
        try {
            events.save(new SagaEventEntity(developerId, workflowId, definition, failedStep,
                    String.join(", ", plan.compensations().stream().map(SagaPlan.Compensation::stepId).toList()),
                    String.join(", ", plan.uncompensated()),
                    plan.complete(), plan.summary()));
        } catch (RuntimeException e) {
            log.warn("Could not record saga event for {}: {}", workflowId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(String developerId) {
        SagaSettingEntity cfg = settings.findById(developerId)
                .orElseGet(() -> new SagaSettingEntity(developerId));
        List<SagaEventEntity> rows = events.findByDeveloperIdOrderByCreatedAtDesc(
                developerId, PageRequest.of(0, 100));

        long compensated = 0;
        long stranded = 0;
        long partial = 0;
        List<Map<String, Object>> recent = new ArrayList<>();
        for (SagaEventEntity r : rows) {
            compensated += count(r.getCompensated());
            stranded += count(r.getUncompensated());
            if (!r.isComplete()) {
                partial++;
            }
            if (recent.size() < 30) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("workflowId", r.getWorkflowId());
                m.put("definition", r.getDefinition());
                m.put("failedStep", r.getFailedStep());
                m.put("compensated", split(r.getCompensated()));
                m.put("uncompensated", split(r.getUncompensated()));
                m.put("complete", r.isComplete());
                m.put("summary", r.getSummary());
                m.put("at", r.getCreatedAt());
                recent.add(m);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.isEnabled());
        out.put("rollbacks", rows.size());
        out.put("stepsCompensated", compensated);
        out.put("stepsStranded", stranded);
        out.put("partialRollbacks", partial);
        out.put("recent", recent);
        return out;
    }

    @Transactional
    public Map<String, Object> clear(String developerId) {
        events.deleteByDeveloperId(developerId);
        return status(developerId);
    }

    private static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : List.of(csv.split(",\\s*"));
    }

    private static long count(String csv) {
        return split(csv).size();
    }
}
