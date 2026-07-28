package io.continuum.degradation;

import io.continuum.persistence.entity.DegradationEventEntity;
import io.continuum.persistence.entity.DegradationSettingEntity;
import io.continuum.persistence.repository.DegradationEventRepository;
import io.continuum.persistence.repository.DegradationSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-tenant graceful degradation, and the record of when it fired. */
@Service
public class DegradationService {

    private static final Logger log = LoggerFactory.getLogger(DegradationService.class);

    private final DegradationSettingRepository settings;
    private final DegradationEventRepository events;

    public DegradationService(DegradationSettingRepository settings,
                              DegradationEventRepository events) {
        this.settings = settings;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public boolean enabled(String developerId) {
        try {
            return settings.findById(developerId)
                    .map(DegradationSettingEntity::isEnabled).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Records a descent. Never throws — the answer matters more than the note. */
    @Transactional
    public void record(String developerId, DegradationLadder.Outcome outcome) {
        try {
            events.save(new DegradationEventEntity(developerId, outcome.rung().name(),
                    outcome.reason()));
        } catch (RuntimeException e) {
            log.debug("Could not record degradation: {}", e.getMessage());
        }
    }

    @Transactional
    public Map<String, Object> configure(String developerId, Boolean enabled) {
        DegradationSettingEntity cfg = settings.findById(developerId)
                .orElseGet(() -> new DegradationSettingEntity(developerId));
        if (enabled != null) {
            cfg.setEnabled(enabled);
        }
        settings.save(cfg);
        return status(developerId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(String developerId) {
        List<DegradationEventEntity> rows = events.findByDeveloperIdOrderByCreatedAtDesc(
                developerId, PageRequest.of(0, 200));
        Map<String, Long> byRung = new LinkedHashMap<>();
        for (DegradationEventEntity r : rows) {
            byRung.merge(r.getRung(), 1L, Long::sum);
        }
        List<Map<String, Object>> recent = new ArrayList<>();
        for (DegradationEventEntity r : rows.subList(0, Math.min(30, rows.size()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rung", r.getRung());
            m.put("reason", r.getReason());
            m.put("at", r.getCreatedAt());
            recent.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled(developerId));
        out.put("events", rows.size());
        out.put("byRung", byRung);
        out.put("recent", recent);
        return out;
    }

    @Transactional
    public Map<String, Object> clear(String developerId) {
        events.deleteByDeveloperId(developerId);
        return status(developerId);
    }
}
