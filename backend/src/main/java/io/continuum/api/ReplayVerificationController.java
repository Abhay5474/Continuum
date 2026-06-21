package io.continuum.api;

import io.continuum.persistence.entity.ReplayVerificationReportEntity;
import io.continuum.persistence.repository.ReplayVerificationReportRepository;
import io.continuum.semantic.ReplayVerificationPolicy;
import io.continuum.semantic.ReplayVerificationReport;
import io.continuum.semantic.SemanticReplayVerifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Extension 1 — Semantic Replay Verification API.
 */
@RestController
@RequestMapping("/api/replay")
public class ReplayVerificationController {

    private final SemanticReplayVerifier verifier;
    private final ReplayVerificationReportRepository reports;

    public ReplayVerificationController(SemanticReplayVerifier verifier,
                                        ReplayVerificationReportRepository reports) {
        this.verifier = verifier;
        this.reports = reports;
    }

    /** Re-run a workflow's LLM activities against the current provider and score drift. */
    @PostMapping("/verify/{workflowId}")
    public ReplayVerificationReport verify(@PathVariable String workflowId,
                                           @RequestParam(required = false) Double passThreshold,
                                           @RequestParam(defaultValue = "false") boolean useLlmJudge) {
        ReplayVerificationPolicy d = ReplayVerificationPolicy.defaults();
        ReplayVerificationPolicy policy = new ReplayVerificationPolicy(
                passThreshold != null ? passThreshold : d.passThreshold(),
                d.intentHardFailBelow(), d.wSimilarity(), d.wIntent(), d.wTool(),
                d.wStructured(), d.wConstraint(), useLlmJudge);
        return verifier.verify(workflowId, policy);
    }

    @GetMapping("/reports/{workflowId}")
    public List<ReplayVerificationReportEntity> reports(@PathVariable String workflowId) {
        return verifier.reportsFor(workflowId);
    }

    /** Recent verification reports across all workflows — feeds the drift-trend chart. */
    @GetMapping("/trends")
    public List<ReplayVerificationReportEntity> trends(@RequestParam(defaultValue = "100") int limit) {
        return reports.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)).getContent();
    }
}
