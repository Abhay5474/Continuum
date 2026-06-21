package io.continuum.semantic;

import io.continuum.persistence.entity.ReplayVerificationReportEntity;

import java.util.List;

/**
 * Aggregate semantic-replay verdict for a workflow.
 *
 * <ul>
 *   <li><b>replayConfidence</b> — mean overall equivalence score (would today's
 *       run still be trusted as a replay?).</li>
 *   <li><b>semanticDrift</b> — 1 - mean lexical similarity (how much wording has
 *       moved).</li>
 *   <li><b>decisionConsistency</b> — mean intent agreement over outputs that
 *       expressed a decision (have any conclusions reversed?).</li>
 * </ul>
 */
public record ReplayVerificationReport(
        String workflowId,
        int verifiedActivities,
        int passed,
        int failed,
        double replayConfidence,
        double semanticDrift,
        double decisionConsistency,
        List<ReplayVerificationReportEntity> items) {
}
