package io.continuum.loops;

import io.continuum.persistence.entity.LoopEventEntity;
import io.continuum.persistence.entity.LoopSettingEntity;
import io.continuum.persistence.repository.LoopEventRepository;
import io.continuum.persistence.repository.LoopSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-tenant loop detection, and the record of what it caught. */
@Service
public class LoopGuardService {

    private static final Logger log = LoggerFactory.getLogger(LoopGuardService.class);

    private final LoopSettingRepository settings;
    private final LoopEventRepository events;

    public LoopGuardService(LoopSettingRepository settings, LoopEventRepository events) {
        this.settings = settings;
        this.events = events;
    }

    /** Raised when a run is stopped for looping. */
    public static class LoopHaltedException extends RuntimeException {
        private final LoopDetector.Verdict verdict;

        public LoopHaltedException(LoopDetector.Verdict verdict) {
            super("Stopped: " + verdict.reason());
            this.verdict = verdict;
        }

        public LoopDetector.Verdict verdict() {
            return verdict;
        }
    }

    @Transactional(readOnly = true)
    public LoopSettingEntity settingsFor(String developerId) {
        return settings.findById(developerId).orElseGet(() -> new LoopSettingEntity(developerId));
    }

    /**
     * Inspects an agent's history and records anything found.
     *
     * @throws LoopHaltedException in HALT mode when a loop is detected
     */
    @Transactional
    public LoopDetector.Verdict check(String developerId, String workflowId, List<String> steps,
                                      List<Boolean> progress) {
        LoopSettingEntity cfg = settingsFor(developerId);
        if (!cfg.isEnabled()) {
            return null;
        }
        LoopDetector.Verdict v = LoopDetector.inspect(steps, progress);
        if (!v.looping()) {
            return v;
        }
        boolean halt = cfg.getMode() == LoopSettingEntity.Mode.HALT;
        try {
            events.save(new LoopEventEntity(developerId, workflowId, v.kind().name(), v.at(),
                    v.confidence(), v.reason(), String.join(" | ", v.evidence()), halt));
        } catch (RuntimeException e) {
            log.debug("Could not record loop event: {}", e.getMessage());
        }
        if (halt) {
            throw new LoopHaltedException(v);
        }
        return v;
    }

    @Transactional
    public Map<String, Object> configure(String developerId, Boolean enabled, String mode) {
        LoopSettingEntity cfg = settingsFor(developerId);
        if (enabled != null) {
            cfg.setEnabled(enabled);
        }
        if (mode != null) {
            try {
                cfg.setMode(LoopSettingEntity.Mode.valueOf(mode.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Mode is MONITOR or HALT.");
            }
        }
        settings.save(cfg);
        return status(developerId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(String developerId) {
        LoopSettingEntity cfg = settingsFor(developerId);
        List<LoopEventEntity> rows = events.findByDeveloperIdOrderByCreatedAtDesc(
                developerId, PageRequest.of(0, 100));
        Map<String, Long> byKind = new LinkedHashMap<>();
        long halted = 0;
        for (LoopEventEntity r : rows) {
            byKind.merge(r.getKind(), 1L, Long::sum);
            if (r.isHalted()) {
                halted++;
            }
        }
        List<Map<String, Object>> recent = new ArrayList<>();
        for (LoopEventEntity r : rows.subList(0, Math.min(30, rows.size()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", r.getKind());
            m.put("stepIndex", r.getStepIndex());
            m.put("confidence", r.getConfidence());
            m.put("reason", r.getReason());
            m.put("evidence", r.getEvidence());
            m.put("halted", r.isHalted());
            m.put("workflowId", r.getWorkflowId());
            m.put("at", r.getCreatedAt());
            recent.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.isEnabled());
        out.put("mode", cfg.getMode().name());
        out.put("detected", rows.size());
        out.put("halted", halted);
        out.put("byKind", byKind);
        out.put("recent", recent);
        return out;
    }

    @Transactional
    public Map<String, Object> clear(String developerId) {
        events.deleteByDeveloperId(developerId);
        return status(developerId);
    }
}
