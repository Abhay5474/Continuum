package io.continuum.api;

import io.continuum.portal.PortalAuthFilter;
import io.continuum.specialist.PipelineService;
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
                body.systemPrompt(), body.steps());
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(HttpServletRequest req, @PathVariable Long id,
                                      @RequestBody PipelineSettings body) {
        return pipelines.update(dev(req), id, body.description(), body.systemPrompt(),
                body.steps(), body.enabled());
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
        m.put("trace", r.chain() == null ? List.of() : r.chain());
        return m;
    }

    public record NewPipeline(String name, String description, String inputKind,
                              String systemPrompt, List<Long> steps) {
    }

    public record PipelineSettings(String description, String systemPrompt, List<Long> steps,
                                   Boolean enabled) {
    }

    public record RunRequest(Map<String, Object> input, String prompt) {
    }
}
