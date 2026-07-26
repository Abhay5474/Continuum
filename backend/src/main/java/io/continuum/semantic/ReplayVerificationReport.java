package io.continuum.semantic;

import io.continuum.declarative.DeterministicReplayVerifier;
import io.continuum.persistence.entity.ReplayVerificationReportEntity;

import java.util.List;

/**
 * What a replay verification concluded, and — just as importantly — what it was
 * able to check.
 *
 * <p>The scores used to be reported unconditionally, so a workflow with nothing
 * verifiable came back with a confidence of 1.0 having examined nothing. A green
 * light that checked nothing is worse than no light, so the scores are now
 * nullable and {@link #verdict} says which of the two happened.
 *
 * <p>Two kinds of check, because two kinds of step:
 * <ul>
 *   <li><b>deterministic</b> — for steps whose inputs are recorded, replay the
 *       decisions and confirm the same calls would be made. No I/O, so it is
 *       safe on production history.</li>
 *   <li><b>semantic</b> — for model calls, regenerate the output against today's
 *       provider and score whether it still means the same thing.</li>
 * </ul>
 */
public record ReplayVerificationReport(
        String workflowId,
        String verdict,
        int verifiedActivities,
        int passed,
        int failed,
        Double replayConfidence,
        Double semanticDrift,
        Double decisionConsistency,
        DeterministicReplayVerifier.Result deterministic,
        List<ReplayVerificationReportEntity> items) {

    /** Nothing in this workflow could be verified — say so rather than pass it. */
    public static final String NOTHING_TO_VERIFY = "NOTHING_TO_VERIFY";
    public static final String VERIFIED = "VERIFIED";
    public static final String DIVERGED = "DIVERGED";
}
