package io.continuum.api;

import io.continuum.aichaos.AiChaosEngine;
import io.continuum.aichaos.AiFailureType;
import io.continuum.persistence.entity.AiChaosEventEntity;
import io.continuum.persistence.entity.WorkflowInstanceEntity;
import io.continuum.persistence.entity.WorkflowStatus;
import io.continuum.persistence.repository.AiChaosEventRepository;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extension 3 — AI Chaos Lab API. Toggle AI failure injection and read real
 * survival/recovery metrics derived from injected workflows' actual outcomes.
 */
@RestController
@RequestMapping("/api/ai-chaos")
public class AiChaosController {

    private final AiChaosEngine engine;
    private final AiChaosEventRepository events;
    private final WorkflowInstanceRepository instances;

    public AiChaosController(AiChaosEngine engine, AiChaosEventRepository events,
                             WorkflowInstanceRepository instances) {
        this.engine = engine;
        this.events = events;
        this.instances = instances;
    }

    @GetMapping
    public Map<String, Object> state() {
        return Map.of("active", engine.isActive(), "rates", engine.state());
    }

    @PostMapping("/rate")
    public Map<String, Object> setRate(@RequestParam AiFailureType type, @RequestParam double rate) {
        engine.setRate(type, rate);
        return state();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        engine.reset();
        return state();
    }

    @GetMapping("/events")
    public List<AiChaosEventEntity> events(@RequestParam(defaultValue = "100") int limit) {
        return events.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)).getContent();
    }

    /**
     * Real metrics: of the workflows that suffered an AI injection, how many
     * still completed (survived) vs failed. Computed by joining injection events
     * to actual workflow statuses.
     */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        long total = events.count();

        Map<String, Long> byType = new LinkedHashMap<>();
        for (var tc : events.countByType()) {
            byType.put(tc.getType(), tc.getCount());
        }

        List<String> affectedIds = events.distinctAffectedWorkflowIds();
        long survived = 0, failed = 0, running = 0;
        for (WorkflowInstanceEntity wf : instances.findAllById(affectedIds)) {
            if (wf.getStatus() == WorkflowStatus.COMPLETED) survived++;
            else if (wf.getStatus() == WorkflowStatus.FAILED) failed++;
            else running++;
        }
        long terminal = survived + failed;
        double survivalRate = terminal == 0 ? 1.0 : (double) survived / terminal;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalInjections", total);
        out.put("injectionsByType", byType);
        out.put("affectedWorkflows", affectedIds.size());
        out.put("survived", survived);
        out.put("failed", failed);
        out.put("running", running);
        out.put("workflowSurvivalRate", survivalRate);
        return out;
    }
}
