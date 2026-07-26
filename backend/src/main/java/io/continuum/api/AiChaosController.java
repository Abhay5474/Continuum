package io.continuum.api;

import io.continuum.aichaos.AiChaosEngine;
import io.continuum.aichaos.AiFailureType;
import io.continuum.persistence.entity.AiChaosEventEntity;
import io.continuum.persistence.entity.WorkflowInstanceEntity;
import io.continuum.persistence.entity.WorkflowStatus;
import io.continuum.persistence.repository.AiChaosEventRepository;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import io.continuum.portal.RequestScope;
import jakarta.servlet.http.HttpServletRequest;
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

    /** Null for the operator (engine-wide profile), otherwise the tenant's own. */
    private String scope(HttpServletRequest req) {
        return RequestScope.isOperator(req) ? null : RequestScope.requireDeveloper(req);
    }

    @GetMapping
    public Map<String, Object> state(HttpServletRequest req) {
        String s = scope(req);
        return Map.of("active", engine.isActive(s), "rates", engine.state(s),
                "scope", s == null ? "engine" : "account");
    }

    @PostMapping("/rate")
    public Map<String, Object> setRate(@RequestParam AiFailureType type, @RequestParam double rate,
                                       HttpServletRequest req) {
        engine.setRate(scope(req), type, rate);
        return state(req);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(HttpServletRequest req) {
        engine.reset(scope(req));
        return state(req);
    }

    /** The caller's own injections; engine-wide only for the operator. */
    @GetMapping("/events")
    public List<AiChaosEventEntity> events(@RequestParam(defaultValue = "100") int limit,
                                           HttpServletRequest req) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(500, limit)));
        return (RequestScope.isOperator(req)
                ? events.findAllByOrderByCreatedAtDesc(page)
                : events.findByDeveloperIdOrderByCreatedAtDesc(RequestScope.requireDeveloper(req), page))
                .getContent();
    }

    /**
     * Real metrics: of the workflows that suffered an AI injection, how many
     * still completed (survived) vs failed. Computed by joining injection events
     * to actual workflow statuses.
     */
    @GetMapping("/metrics")
    public Map<String, Object> metrics(HttpServletRequest req) {
        boolean engineWide = RequestScope.isOperator(req);
        String dev = engineWide ? null : RequestScope.requireDeveloper(req);

        long total = engineWide ? events.count() : events.countByDeveloperId(dev);

        Map<String, Long> byType = new LinkedHashMap<>();
        for (var tc : engineWide ? events.countByType() : events.countByType(dev)) {
            byType.put(tc.getType(), tc.getCount());
        }

        List<String> affectedIds = engineWide
                ? events.distinctAffectedWorkflowIds()
                : events.distinctAffectedWorkflowIds(dev);
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
