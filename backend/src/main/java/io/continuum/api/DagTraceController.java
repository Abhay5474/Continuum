package io.continuum.api;

import io.continuum.dag.ConsensusDagService;
import io.continuum.persistence.entity.DagRunEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * V6 — read-only trace APIs for the Execution Command Center UI. Open like the
 * other dashboard APIs (/api/workflows, /api/gateway/stats); the projection
 * contains no secrets, only the structured verification record.
 */
@RestController
@RequestMapping("/api/dag")
public class DagTraceController {

    private final ConsensusDagService dag;

    public DagTraceController(ConsensusDagService dag) {
        this.dag = dag;
    }

    @GetMapping("/runs")
    public List<DagRunEntity> runs(@RequestParam(required = false) String developerId) {
        return dag.recentRuns(developerId);
    }

    @GetMapping("/trace/{workflowId}")
    public Map<String, Object> trace(@PathVariable String workflowId) {
        return dag.trace(workflowId);
    }
}
