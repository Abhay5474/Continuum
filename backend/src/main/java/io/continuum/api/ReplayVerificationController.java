package io.continuum.api;

import io.continuum.persistence.entity.ReplayVerificationReportEntity;
import io.continuum.persistence.repository.ReplayVerificationReportRepository;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import io.continuum.portal.RequestScope;
import io.continuum.semantic.ReplayVerificationPolicy;
import io.continuum.semantic.ReplayVerificationReport;
import io.continuum.semantic.SemanticReplayVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Replay verification API.
 *
 * <p>Every route here reaches into a workflow's recorded history, which is the
 * customer's business data — request bodies, responses, model outputs. So each
 * one is checked against the workflow's owner rather than merely requiring a
 * session; being signed in as somebody is not the same as being signed in as the
 * right somebody.
 */
@RestController
@RequestMapping("/api/replay")
public class ReplayVerificationController {

    private final SemanticReplayVerifier verifier;
    private final ReplayVerificationReportRepository reports;
    private final WorkflowInstanceRepository instances;

    public ReplayVerificationController(SemanticReplayVerifier verifier,
                                        ReplayVerificationReportRepository reports,
                                        WorkflowInstanceRepository instances) {
        this.verifier = verifier;
        this.reports = reports;
        this.instances = instances;
    }

    /** Refuses unless the caller owns the workflow (or is the operator). */
    private void requireOwnership(HttpServletRequest req, String workflowId) {
        String owner = instances.findById(workflowId)
                .map(w -> w.getDeveloperId())
                // A workflow that does not exist and one belonging to someone else
                // answer identically, so this cannot be used to probe for ids.
                .orElseThrow(RequestScope.ForbiddenException::new);
        RequestScope.requireOwner(req, owner);
    }

    /** Re-verify a run against its recorded history. */
    @PostMapping("/verify/{workflowId}")
    public ReplayVerificationReport verify(@PathVariable String workflowId,
                                           @RequestParam(required = false) Double passThreshold,
                                           @RequestParam(defaultValue = "false") boolean useLlmJudge,
                                           HttpServletRequest req) {
        requireOwnership(req, workflowId);
        ReplayVerificationPolicy d = ReplayVerificationPolicy.defaults();
        ReplayVerificationPolicy policy = new ReplayVerificationPolicy(
                passThreshold != null ? passThreshold : d.passThreshold(),
                d.intentHardFailBelow(), d.wSimilarity(), d.wIntent(), d.wTool(),
                d.wStructured(), d.wConstraint(), useLlmJudge);
        return verifier.verify(workflowId, policy);
    }

    @GetMapping("/reports/{workflowId}")
    public List<ReplayVerificationReportEntity> reports(@PathVariable String workflowId,
                                                        HttpServletRequest req) {
        requireOwnership(req, workflowId);
        return verifier.reportsFor(workflowId);
    }

    /** Recent verifications — the caller's own, unless the caller is the operator. */
    @GetMapping("/trends")
    public List<ReplayVerificationReportEntity> trends(@RequestParam(defaultValue = "100") int limit,
                                                       HttpServletRequest req) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(500, limit)));
        String dev = RequestScope.developerId(req);
        return (dev == null && RequestScope.isOperator(req)
                ? reports.findAllByOrderByCreatedAtDesc(page)
                : reports.findByDeveloperIdOrderByCreatedAtDesc(RequestScope.requireDeveloper(req), page))
                .getContent();
    }
}
