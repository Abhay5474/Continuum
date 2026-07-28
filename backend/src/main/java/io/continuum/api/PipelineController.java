package io.continuum.api;

import io.continuum.portal.PortalAuthFilter;
import io.continuum.specialist.PipelineService;
import io.continuum.specialist.PipelineStep;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline configuration, from the console.
 *
 * <p>Session-authenticated and tenant-scoped. The matching runtime endpoint an
 * external application calls lives in
 * {@link io.continuum.gateway.PipelineGatewayController} and authenticates with
 * an API key instead — the same pipeline, reached two ways, because the person
 * wiring it up and the program using it are not the same principal.
 */
@RestController
@RequestMapping("/api/portal/developer/pipelines")
public class PipelineController {

    private final PipelineService pipelines;

    public PipelineController(PipelineService pipelines) {
        this.pipelines = pipelines;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req) {
        return pipelines.list(dev(req));
    }

    @PostMapping
    public Map<String, Object> create(HttpServletRequest req, @RequestBody NewPipeline body) {
        return pipelines.create(dev(req), body.name(), body.description(), body.inputKind(),
                body.systemPrompt(), toSteps(body.steps(), body.routing()));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(HttpServletRequest req, @PathVariable Long id,
                                      @RequestBody PipelineSettings body) {
        return pipelines.update(dev(req), id, body.description(), body.systemPrompt(),
                toSteps(body.steps(), body.routing()), body.enabled(), body.policyEnabled(),
                body.strongThreshold(), body.weakThreshold(), body.declineOnNoEvidence(),
                body.routingEnabled(), body.verificationMode());
    }

    /**
     * Accepts either {@code steps: [3, 7]} or {@code routing: [{specialistId, when,
     * pattern}]}.
     *
     * <p>The bare form is what every existing caller sends, and it keeps working
     * — it means "run always", which is what it has always meant. When both are
     * present the richer one wins, because it is the only one that can express a
     * condition.
     */
    private static List<PipelineStep> toSteps(List<Long> steps, List<StepSpec> routing) {
        if (routing != null) {
            return routing.stream()
                    .map(r -> new PipelineStep(r.specialistId(),
                            r.when() == null ? PipelineStep.Condition.ALWAYS
                                    : PipelineStep.Condition.valueOf(r.when()),
                            r.pattern()))
                    .toList();
        }
        if (steps != null) {
            return steps.stream().map(PipelineStep::always).toList();
        }
        return null;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(HttpServletRequest req, @PathVariable Long id) {
        pipelines.delete(dev(req), id);
        return Map.of("deleted", true);
    }

    /**
     * Runs a pipeline from the console, exactly as an application would.
     *
     * <p>Here so a developer can see the chain before pointing production at it.
     * It goes through the same {@link PipelineService#run} as the gateway route —
     * a test path that skipped a step would be worse than no test path at all.
     */
    @PostMapping("/{name}/run")
    public Map<String, Object> run(HttpServletRequest req, @PathVariable String name,
                                   @RequestBody RunRequest body) {
        PipelineService.Run r = pipelines.run(dev(req), name, body.input(), body.prompt());
        return describe(r);
    }

    /** One response shape, shared with the gateway route. */
    public static Map<String, Object> describe(PipelineService.Run r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("response", r.response() == null ? "" : r.response());
        m.put("traceId", r.traceId());
        m.put("findings", r.findings());
        m.put("evidenceConfidence", r.topConfidence());
        m.put("anythingFound", r.anythingFound());
        // Separate from anythingFound on purpose: a clean image and a dead
        // specialist both report zero findings, and an application that shows
        // "all clear" for the second one is worse than one that shows nothing.
        m.put("analysisRan", r.analysisRan());
        m.put("model", r.model() == null ? "" : r.model());
        m.put("cost", r.cost());
        m.put("latencyMs", r.latencyMs());
        // Null when the policy is off, rather than a hollow "action: NONE" —
        // an application can then tell "not configured" from "configured and it
        // chose to pass", which are different facts about the answer.
        m.put("policy", r.policy());
        m.put("compliance", r.compliance());
        m.put("verification", r.verification());
        m.put("trace", r.chain() == null ? List.of() : r.chain());
        return m;
    }

    public record NewPipeline(String name, String description, String inputKind,
                              String systemPrompt, List<Long> steps, List<StepSpec> routing) {
    }

    public record PipelineSettings(String description, String systemPrompt, List<Long> steps,
                                   List<StepSpec> routing, Boolean enabled, Boolean policyEnabled,
                                   Double strongThreshold, Double weakThreshold,
                                   Boolean declineOnNoEvidence, Boolean routingEnabled,
                                   String verificationMode) {
    }

    /** One step with its condition. */
    public record StepSpec(Long specialistId, String when, String pattern) {
    }

    public record RunRequest(Map<String, Object> input, String prompt) {
    }
}
