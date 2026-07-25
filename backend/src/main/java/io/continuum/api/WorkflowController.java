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

    @PostMapping
    public StartWorkflowResponse start(@RequestBody StartWorkflowRequest request, HttpServletRequest http) {
        String inputJson = request.input() == null ? "{}" : json.write(request.input());
        String id = engine.startWorkflow(request.workflowType(), inputJson, request.workflowId(),
                RequestScope.developerId(http));
        return new StartWorkflowResponse(id, "RUNNING");
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

    @GetMapping("/{id}/events")
    public List<EventView> events(@PathVariable String id, HttpServletRequest http) {
        RequestScope.requireOwner(http, query.ownerOf(id));
        return query.detail(id).events();
    }
}
