package io.continuum.api;

import io.continuum.api.dto.Dtos.*;
import io.continuum.common.Json;
import io.continuum.core.engine.WorkflowEngine;
import io.continuum.portal.RequestScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Workflow APIs for the console. Every read is scoped to the signed-in developer:
 * workflows they started are theirs, and a workflow owned by someone else is a 403
 * rather than a readable event log. Operator sessions see the whole engine.
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowEngine engine;
    private final WorkflowQueryService query;
    private final Json json;

    public WorkflowController(WorkflowEngine engine, WorkflowQueryService query, Json json) {
        this.engine = engine;
        this.query = query;
        this.json = json;
    }

    /**
     * Starts a workflow, idempotently when the caller names the id.
     *
     * <p>Naming an id is how a client makes a retry safe, so a repeat has to
     * answer as the first call did: it returns the existing run and its real
     * status rather than claiming it is RUNNING. Two repeats arriving at the same
     * instant used to race to insert the row and the loser got a 500; it now
     * gets the same answer as the winner. An id already used by another account,
     * or for a different workflow type, is refused rather than silently
     * answered with somebody else's run.
     */
    @PostMapping
    public StartWorkflowResponse start(@RequestBody StartWorkflowRequest request, HttpServletRequest http) {
        if (request.workflowType() == null || request.workflowType().isBlank()) {
            throw new IllegalArgumentException("workflowType is required, e.g. \"DurableDemo\".");
        }
        String dev = RequestScope.developerId(http);
        String requested = request.workflowId() == null || request.workflowId().isBlank()
                ? null : request.workflowId().trim();
        if (requested != null && requested.length() > 128) {
            throw new IllegalArgumentException("workflowId must be at most 128 characters.");
        }
        if (requested != null) {
            var existing = query.find(requested);
            if (existing.isPresent()) {
                return repeat(requested, existing.get(), request.workflowType(), dev, http);
            }
        }
        String inputJson = request.input() == null ? "{}" : json.write(request.input());
        try {
            String id = engine.startWorkflow(request.workflowType(), inputJson, requested, dev);
            return new StartWorkflowResponse(id, "RUNNING");
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            var existing = query.find(requested);
            if (requested == null || existing.isEmpty()) {
                throw raced;
            }
            return repeat(requested, existing.get(), request.workflowType(), dev, http);
        }
    }

    private StartWorkflowResponse repeat(String id, WorkflowQueryService.Existing existing, String type,
                                         String dev, HttpServletRequest http) {
        boolean mine = RequestScope.isOperator(http) || (dev != null && dev.equals(existing.ownerId()));
        if (!mine || !existing.workflowType().equals(type)) {
            throw new io.continuum.core.engine.WorkflowIdInUseException(id);
        }
        return new StartWorkflowResponse(id, existing.status());
    }

    @GetMapping
    public List<WorkflowSummary> list(@RequestParam(defaultValue = "100") int limit, HttpServletRequest http) {
        int capped = Math.max(1, Math.min(limit, 500));
        String dev = RequestScope.developerId(http);
        return dev == null ? query.list(capped) : query.listForDeveloper(dev, capped);
    }

    @GetMapping("/{id}")
    public WorkflowDetail get(@PathVariable String id, HttpServletRequest http) {
        RequestScope.requireOwner(http, query.ownerOf(id));
        return query.detail(id);
    }

    /**
     * Stops a running workflow. Safe to repeat: a finished workflow is left as
     * it ended, and the answer says how that was.
     */
    @PostMapping("/{id}/cancel")
    public StartWorkflowResponse cancel(@PathVariable String id,
                                        @RequestBody(required = false) java.util.Map<String, String> body,
                                        HttpServletRequest http) {
        RequestScope.requireOwner(http, query.ownerOf(id));
        String reason = body == null ? null : body.get("reason");
        if (reason != null && reason.length() > 500) {
            reason = reason.substring(0, 500);
        }
        return new StartWorkflowResponse(id, engine.cancel(id, reason).name());
    }

    @GetMapping("/{id}/events")
    public List<EventView> events(@PathVariable String id, HttpServletRequest http) {
        RequestScope.requireOwner(http, query.ownerOf(id));
        return query.detail(id).events();
    }
}
