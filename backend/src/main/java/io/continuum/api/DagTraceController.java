package io.continuum.api;

import io.continuum.dag.ConsensusDagService;
import io.continuum.persistence.entity.DagRunEntity;
import io.continuum.portal.RequestScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only verification trace APIs for the console.
 *
 * <p>Tenant-scoped: the developer id comes from the authenticated session, never
 * from a query parameter. The previous {@code ?developerId=} parameter let any
 * caller read any tenant's traces.
 */
@RestController
@RequestMapping("/api/dag")
public class DagTraceController {

    private final ConsensusDagService dag;

    public DagTraceController(ConsensusDagService dag) {
        this.dag = dag;
    }

    @GetMapping("/runs")
    public List<DagRunEntity> runs(HttpServletRequest req) {
        return dag.recentRuns(RequestScope.developerId(req));
    }

    @GetMapping("/trace/{workflowId}")
    public Map<String, Object> trace(@PathVariable String workflowId, HttpServletRequest req) {
        Map<String, Object> trace = dag.trace(workflowId);
        // A trace is only readable by the tenant that produced it.
        Object run = trace.get("run");
        if (run instanceof DagRunEntity r) {
            RequestScope.requireOwner(req, r.getDeveloperId());
        } else if (!RequestScope.isOperator(req)) {
            throw new RequestScope.ForbiddenException();
        }
        return trace;
    }
}
