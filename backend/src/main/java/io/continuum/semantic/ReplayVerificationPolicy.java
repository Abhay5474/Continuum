package io.continuum.semantic;

/**
 * Thresholds and weights controlling how a {@link SemanticComparisonResult} is
 * judged pass/fail. Sensible defaults; fully overridable per verification run.
 */
public record ReplayVerificationPolicy(
        double passThreshold,        // overall score required to pass
        double intentHardFailBelow,  // any intent score below this is an automatic fail
        double wSimilarity,
        double wIntent,
        double wTool,
        double wStructured,
        double wConstraint,
        boolean useLlmJudge) {

    public static ReplayVerificationPolicy defaults() {
        return new ReplayVerificationPolicy(
                0.65,   // passThreshold
                0.5,    // intentHardFailBelow — reversed decisions (score 0) always fail
                0.35,   // similarity weight
                0.35,   // intent weight (decision agreement is the strongest "same behavior" signal)
                0.10,   // tool weight
                0.10,   // structured weight
                0.10,   // constraint weight
                false);
    }
}
