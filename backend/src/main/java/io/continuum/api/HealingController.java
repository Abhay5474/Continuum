package io.continuum.api;

import io.continuum.healing.ParadoxResolutionService;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import io.continuum.portal.RequestScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * V5.1.1 — control-plane monitoring for the Paradox Resolution Engine.
 * The engine itself is compulsory and always on; these endpoints only observe
 * (status, per-instance ledger) or dry-run a validation scan.
 */
@RestController
@RequestMapping("/api/gateway/healing")
public class HealingController {

    private final ParadoxResolutionService healing;
    private final WorkflowInstanceRepository instances;

    public HealingController(ParadoxResolutionService healing, WorkflowInstanceRepository instances) {
        this.healing = healing;
        this.instances = instances;
    }

    /**
     * A healing ledger describes one workflow's history, so it is the owner's to
     * read. A missing workflow and someone else's answer the same way.
     */
    private void requireOwnership(HttpServletRequest req, String workflowId) {
        String owner = instances.findById(workflowId)
                .map(w -> w.getDeveloperId())
                .orElseThrow(RequestScope.ForbiddenException::new);
        RequestScope.requireOwner(req, owner);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return healing.status();
    }

    @GetMapping("/workflow/{workflowId}")
    public Map<String, Object> workflow(@PathVariable String workflowId, HttpServletRequest req) {
        requireOwnership(req, workflowId);
        return healing.workflowLedger(workflowId);
    }

    /** Dry-run code-to-history validation. Body: {"workflowId": "..."} (optional). */
    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody(required = false) Map<String, String> body,
                                      HttpServletRequest req) {
        String workflowId = body == null ? null : body.get("workflowId");
        if (workflowId != null) {
            requireOwnership(req, workflowId);
        } else if (!RequestScope.isOperator(req)) {
            // An unqualified scan walks every workflow in the engine.
            throw new RequestScope.ForbiddenException();
        }
        return healing.verify(workflowId);
    }
}
