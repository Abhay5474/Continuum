package io.continuum.api;

import io.continuum.api.dto.Dtos.*;
import io.continuum.common.Json;
import io.continuum.core.engine.WorkflowEngine;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public StartWorkflowResponse start(@RequestBody StartWorkflowRequest request) {
        String inputJson = request.input() == null ? "{}" : json.write(request.input());
        String id = engine.startWorkflow(request.workflowType(), inputJson, request.workflowId());
        return new StartWorkflowResponse(id, "RUNNING");
    }

    @GetMapping
    public List<WorkflowSummary> list(@RequestParam(defaultValue = "100") int limit) {
        return query.list(limit);
    }

    @GetMapping("/{id}")
    public WorkflowDetail get(@PathVariable String id) {
        return query.detail(id);
    }

    @GetMapping("/{id}/events")
    public List<EventView> events(@PathVariable String id) {
        return query.detail(id).events();
    }
}
