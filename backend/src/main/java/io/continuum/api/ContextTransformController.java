package io.continuum.api;

import io.continuum.context.ContextTransformService;
import io.continuum.context.PromptContextService;
import io.continuum.context.RenderBudget;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The context layer, from the console.
 *
 * <p>Entirely new paths. Nothing here touches the specialist, pipeline or
 * gateway routes — a deployment that never calls these behaves exactly as it did
 * before the layer existed.
 */
@RestController
@RequestMapping("/api/portal/developer/context")
public class ContextTransformController {

    private final ContextTransformService service;
    private final PromptContextService promptContext;

    public ContextTransformController(ContextTransformService service,
                                      PromptContextService promptContext) {
        this.service = service;
        this.promptContext = promptContext;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    /**
     * Whether transformed context actually reaches a model, and on which path.
     *
     * <p>The console needs this to stop over-claiming. Pipelines have always
     * transformed; the chat endpoint does so only when this is switched on, and
     * a page that drew one arrow to "the prompt" was describing half the system.
     */
    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return promptContext.status(dev(req));
    }

    /** Turns transformation on or off for {@code /v1/chat/completions}. */
    @PostMapping("/gateway/{action}")
    public Map<String, Object> setGateway(HttpServletRequest req, @PathVariable String action) {
        promptContext.setEnabled(dev(req), "enable".equalsIgnoreCase(action));
        return promptContext.status(dev(req));
    }

    /** What transformations exist, so the console does not hard-code them. */
    @GetMapping("/capabilities")
    public List<Map<String, Object>> capabilities() {
        return service.capabilities();
    }

    /**
     * Transforms an uploaded file.
     *
     * @param budget {@code standard}, {@code summary} or {@code full} — the same
     *               canonical form spoken at three different lengths, because the
     *               useful rendering of a workbook is not the complete one
     */
    @PostMapping(value = "/transform", consumes = "multipart/form-data")
    public Map<String, Object> transformFile(HttpServletRequest req,
                                             @RequestPart("file") MultipartFile file,
                                             @RequestParam(defaultValue = "standard") String budget)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file was uploaded.");
        }
        return service.transformAndRecord(dev(req), file.getBytes(),
                file.getOriginalFilename(), RenderBudget.named(budget)).describe();
    }

    /**
     * Transforms text posted directly — a pasted log or CSV.
     *
     * <p>Separate from the upload route rather than overloaded on content type:
     * a developer trying this from the console has text in their clipboard, not
     * a file on disk, and making them save it first to see what the layer does
     * is a reason not to try it.
     */
    @PostMapping(value = "/transform", consumes = "text/plain")
    public Map<String, Object> transformText(HttpServletRequest req,
                                             @org.springframework.web.bind.annotation.RequestBody
                                             String body,
                                             @RequestParam(required = false) String filename,
                                             @RequestParam(defaultValue = "standard") String budget) {
        return service.transformAndRecord(dev(req), body.getBytes(StandardCharsets.UTF_8),
                filename == null || filename.isBlank() ? "pasted-input" : filename,
                RenderBudget.named(budget)).describe();
    }

    /** Recent transformations, for the console's history and totals. */
    @GetMapping("/transforms")
    public List<Map<String, Object>> recent(HttpServletRequest req,
                                            @RequestParam(defaultValue = "25") int limit) {
        return service.recent(dev(req), limit);
    }
}
