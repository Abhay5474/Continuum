package io.continuum.api;

import io.continuum.autopilot.AutopilotService;
import io.continuum.autopilot.PolicyBundleService;
import io.continuum.autopilot.model.AutopilotMode;
import io.continuum.autopilot.model.DeveloperProfile;
import io.continuum.persistence.entity.AutopilotConfigEntity;
import io.continuum.persistence.entity.PolicyBundleEntity;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Autopilot API — session-scoped to the authenticated developer via the existing
 * {@link PortalAuthFilter} (route is under /api/portal/developer/**). Opt-in,
 * bounded, reversible. Autopilot is DISABLED by default.
 */
@RestController
@RequestMapping("/api/portal/developer/autopilot")
public class AutopilotController {

    private final AutopilotService autopilot;
    private final PolicyBundleService bundles;

    public AutopilotController(AutopilotService autopilot, PolicyBundleService bundles) {
        this.autopilot = autopilot;
        this.bundles = bundles;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        String id = dev(req);
        AutopilotConfigEntity config = autopilot.getOrCreateConfig(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", config.isEnabled());
        out.put("autoApply", config.isAutoApply());
        out.put("mode", config.getMode());
        out.put("activeBundleId", config.getActiveBundleId());
        out.put("canaryBundleId", config.getCanaryBundleId());
        out.put("profile", autopilot.profileOf(config));
        bundles.entity(config.getActiveBundleId())
                .ifPresent(b -> out.put("activePolicy", bundles.parse(b)));
        out.put("telemetry", autopilot.snapshot(id));
        return out;
    }

    @PostMapping("/enable")
    public Map<String, Object> enable(HttpServletRequest req, @RequestBody(required = false) EnableRequest body) {
        DeveloperProfile profile = body == null ? null : body.toProfile();
        String mode = body != null && body.mode() != null ? body.mode() : null;
        autopilot.setEnabled(dev(req), true, mode, profile);
        return status(req);
    }

    @PostMapping("/disable")
    public Map<String, Object> disable(HttpServletRequest req) {
        autopilot.setEnabled(dev(req), false, null, null);
        return status(req);
    }

    @PutMapping("/profile")
    public Map<String, Object> profile(HttpServletRequest req, @RequestBody EnableRequest body) {
        autopilot.setProfile(dev(req), body.toProfile());
        return status(req);
    }

    @PutMapping("/auto-apply")
    public Map<String, Object> autoApply(HttpServletRequest req, @RequestParam boolean value) {
        autopilot.setAutoApply(dev(req), value);
        return status(req);
    }

    /** Trigger one loop iteration now (observe → propose → verify → maybe canary). */
    @PostMapping("/propose")
    public Map<String, Object> propose(HttpServletRequest req) {
        autopilot.runLoopFor(dev(req));
        return Map.of("recommendations", autopilot.recommendations(dev(req)),
                "decisions", autopilot.decisionLog(dev(req), 10));
    }

    @GetMapping("/bundles")
    public List<PolicyBundleEntity> bundles(HttpServletRequest req) {
        return autopilot.history(dev(req));
    }

    @GetMapping("/active-bundle")
    public Object activeBundle(HttpServletRequest req) {
        AutopilotConfigEntity config = autopilot.getOrCreateConfig(dev(req));
        return bundles.entity(config.getActiveBundleId())
                .map(b -> (Object) Map.of("entity", b, "policy", bundles.parse(b)))
                .orElse(Map.of("policy", "none"));
    }

    @GetMapping("/recommendations")
    public List<?> recommendations(HttpServletRequest req) {
        return autopilot.recommendations(dev(req));
    }

    @PostMapping("/recommendations/{id}/accept")
    public Map<String, Object> accept(HttpServletRequest req, @PathVariable Long id) {
        autopilot.acceptRecommendation(dev(req), id);
        return status(req);
    }

    @PostMapping("/recommendations/{id}/reject")
    public Map<String, Object> reject(HttpServletRequest req, @PathVariable Long id) {
        autopilot.rejectRecommendation(dev(req), id);
        return Map.of("rejected", true);
    }

    @PostMapping("/canary/start/{bundleId}")
    public Object startCanary(HttpServletRequest req, @PathVariable Long bundleId) {
        return autopilot.startCanary(dev(req), bundleId);
    }

    @GetMapping("/canary")
    public List<?> canaries(HttpServletRequest req) {
        return autopilot.canaryRuns(dev(req));
    }

    @PostMapping("/rollback")
    public Map<String, Object> rollback(HttpServletRequest req) {
        autopilot.manualRollback(dev(req));
        return status(req);
    }

    @GetMapping("/rollbacks")
    public List<?> rollbacks(HttpServletRequest req) {
        return autopilot.rollbackHistory(dev(req));
    }

    @GetMapping("/decisions")
    public List<?> decisions(HttpServletRequest req, @RequestParam(defaultValue = "50") int limit) {
        return autopilot.decisionLog(dev(req), limit);
    }

    @PostMapping("/feedback")
    public Map<String, Object> feedback(HttpServletRequest req, @RequestBody FeedbackRequest body) {
        autopilot.addFeedback(dev(req), body.requestRef(), body.score(), body.comment());
        return Map.of("recorded", true);
    }

    public record EnableRequest(String applicationName, String goal, Double maxCostPerRequest,
                                Long maxLatencyMs, List<String> allowedProviders,
                                List<String> preferredModelClasses, String mode) {
        DeveloperProfile toProfile() {
            AutopilotMode m;
            try {
                m = mode == null ? AutopilotMode.BALANCED : AutopilotMode.valueOf(mode);
            } catch (Exception e) {
                m = AutopilotMode.BALANCED;
            }
            return new DeveloperProfile(
                    applicationName == null ? "My AI App" : applicationName,
                    goal == null ? "reliable, cost-aware responses" : goal,
                    maxCostPerRequest == null ? 0.02 : maxCostPerRequest,
                    maxLatencyMs == null ? 5000 : maxLatencyMs,
                    allowedProviders == null ? List.of("gemini", "groq", "mock") : allowedProviders,
                    preferredModelClasses == null ? List.of() : preferredModelClasses,
                    m);
        }
    }

    public record FeedbackRequest(String requestRef, double score, String comment) {
    }
}
