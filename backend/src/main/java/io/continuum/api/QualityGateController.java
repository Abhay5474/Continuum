package io.continuum.api;

import io.continuum.portal.PortalAuthFilter;
import io.continuum.quality.QualityGateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Quality gate settings and evidence. Tenant-scoped; OFF by default. */
@RestController
@RequestMapping("/api/portal/developer/quality")
public class QualityGateController {

    private final QualityGateService quality;
    private final io.continuum.quality.AnswerRepairService repairs;

    public QualityGateController(QualityGateService quality,
                                 io.continuum.quality.AnswerRepairService repairs) {
        this.quality = quality;
        this.repairs = repairs;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return quality.status(dev(req));
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest req, @RequestBody Settings body) {
        return quality.configure(dev(req), body.mode(), body.threshold(),
                body.maxRepairs(), body.budgetMs(), body.repairEngineEnabled());
    }

    @GetMapping("/checks")
    public List<Map<String, Object>> checks(HttpServletRequest req,
                                            @RequestParam(defaultValue = "30") int limit) {
        return quality.recent(dev(req), limit);
    }

    @DeleteMapping
    public Map<String, Object> clear(HttpServletRequest req) {
        return quality.clear(dev(req));
    }

    /** Attempt-by-attempt record, including the attempts that were discarded. */
    @GetMapping("/repairs")
    public List<Map<String, Object>> repairs(HttpServletRequest req,
                                             @RequestParam(defaultValue = "30") int limit) {
        return repairs.recent(dev(req), limit);
    }

    @GetMapping("/repairs/summary")
    public Map<String, Object> repairSummary(HttpServletRequest req) {
        return repairs.summary(dev(req));
    }

    @DeleteMapping("/repairs")
    public Map<String, Object> clearRepairs(HttpServletRequest req) {
        repairs.clear(dev(req));
        return Map.of("cleared", true);
    }

    public record Settings(String mode, Double threshold, Integer maxRepairs, Integer budgetMs,
                           Boolean repairEngineEnabled) {
    }
}
