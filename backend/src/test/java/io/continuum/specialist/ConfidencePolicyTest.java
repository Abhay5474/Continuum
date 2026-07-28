package io.continuum.specialist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The policy is the thing that stops a 0.31 detection becoming confident
 * first-aid advice. These tests pin the band boundaries, and — more importantly
 * — pin that the two zero-finding cases stay apart.
 */
class ConfidencePolicyTest {

    private static ContextBuilder.Context withTop(double confidence) {
        return ContextBuilder.build("triage", "what now?", List.of(
                new ContextBuilder.StepResult("detector",
                        List.of(new SpecialistProvider.Finding("wound", confidence, null)), 0, null)));
    }

    private static ContextBuilder.Context foundNothing() {
        return ContextBuilder.build("triage", "what now?",
                List.of(new ContextBuilder.StepResult("detector", List.of(), 0, null)));
    }

    private static ContextBuilder.Context nothingRan() {
        return ContextBuilder.build("triage", "what now?",
                List.of(new ContextBuilder.StepResult("detector", List.of(), 0, "refused")));
    }

    private static ConfidencePolicy.Decision decide(ContextBuilder.Context c) {
        return ConfidencePolicy.decide(c, ConfidencePolicy.DEFAULT_STRONG,
                ConfidencePolicy.DEFAULT_WEAK, false);
    }

    // --- bands ---------------------------------------------------------------

    @Test
    @DisplayName("Strong evidence passes through untouched")
    void strongPasses() {
        ConfidencePolicy.Decision d = decide(withTop(0.91));

        assertThat(d.band()).isEqualTo(ConfidencePolicy.Band.STRONG);
        assertThat(d.action()).isEqualTo(ConfidencePolicy.Action.PASS);
        // Nothing appended: a strong finding needs no instruction, and adding
        // one would make every answer read as though it were unsure.
        assertThat(d.instruction()).isEmpty();
        assertThat(d.declined()).isFalse();
    }

    @Test
    @DisplayName("Moderate evidence makes the model state its uncertainty")
    void mediumHedges() {
        ConfidencePolicy.Decision d = decide(withTop(0.55));

        assertThat(d.band()).isEqualTo(ConfidencePolicy.Band.MEDIUM);
        assertThat(d.action()).isEqualTo(ConfidencePolicy.Action.HEDGE);
        assertThat(d.instruction()).contains("moderate, not strong").contains("Say so plainly");
    }

    @Test
    @DisplayName("Weak evidence forbids naming a condition")
    void weakRefusesToAdvise() {
        ConfidencePolicy.Decision d = decide(withTop(0.31));

        assertThat(d.band()).isEqualTo(ConfidencePolicy.Band.WEAK);
        assertThat(d.action()).isEqualTo(ConfidencePolicy.Action.ASK_FOR_BETTER_INPUT);
        // The instruction is a constraint on the answer, not a mood. "Be
        // careful" is not actionable; "do not name a condition" is.
        assertThat(d.instruction())
                .contains("Do not name a specific condition")
                .contains("what would be needed");
    }

    @Test
    @DisplayName("The boundaries are inclusive at the top of each band")
    void boundariesAreInclusive() {
        assertThat(decide(withTop(0.70)).band()).isEqualTo(ConfidencePolicy.Band.STRONG);
        assertThat(decide(withTop(0.699)).band()).isEqualTo(ConfidencePolicy.Band.MEDIUM);
        assertThat(decide(withTop(0.40)).band()).isEqualTo(ConfidencePolicy.Band.MEDIUM);
        assertThat(decide(withTop(0.399)).band()).isEqualTo(ConfidencePolicy.Band.WEAK);
    }

    @Test
    @DisplayName("Policy defaults track the context builder's prose bands")
    void defaultsCannotDrift() {
        // If these ever diverge the prompt says "Observed:" directly above an
        // instruction to hedge, and contradicts itself.
        assertThat(ConfidencePolicy.DEFAULT_STRONG).isEqualTo(ContextBuilder.STRONG);
        assertThat(ConfidencePolicy.DEFAULT_WEAK).isEqualTo(ContextBuilder.POSSIBLE);
    }

    // --- the distinction that matters ----------------------------------------

    @Test
    @DisplayName("Found nothing and never looked are different bands")
    void nothingFoundIsNotTheSameAsNothingRan() {
        assertThat(decide(foundNothing()).band()).isEqualTo(ConfidencePolicy.Band.NONE);
        assertThat(decide(nothingRan()).band()).isEqualTo(ConfidencePolicy.Band.UNAVAILABLE);
    }

    @Test
    @DisplayName("An outage is never presented as an all-clear")
    void unavailableForbidsTheAllClear() {
        ConfidencePolicy.Decision d = decide(nothingRan());

        assertThat(d.instruction())
                .contains("not the same")
                .contains("must not present it as an all-clear");
        assertThat(d.reason()).contains("nothing was examined");
    }

    @Test
    @DisplayName("Finding nothing lets the model answer the parts that need no findings")
    void noneStillAllowsAUsefulAnswer() {
        // A clean photo is a real result. Refusing to engage at all would be
        // worse than the problem — the user asked a question.
        assertThat(decide(foundNothing()).instruction())
                .contains("answer that part only")
                .doesNotContain("all-clear");
    }

    // --- declining -----------------------------------------------------------

    @Test
    @DisplayName("Declining skips the model entirely")
    void declineShortCircuits() {
        ConfidencePolicy.Decision d = ConfidencePolicy.decide(nothingRan(),
                ConfidencePolicy.DEFAULT_STRONG, ConfidencePolicy.DEFAULT_WEAK, true);

        assertThat(d.action()).isEqualTo(ConfidencePolicy.Action.DECLINE);
        assertThat(d.declined()).isTrue();
        // No instruction, because there is no model call to instruct.
        assertThat(d.instruction()).isEmpty();
    }

    @Test
    @DisplayName("Declining never fires on evidence that exists, however weak")
    void weakEvidenceIsStillEvidence() {
        // Decline is for the absence of evidence, not for bad evidence. A 0.05
        // finding is still something a model can be told to be careful about.
        ConfidencePolicy.Decision d = ConfidencePolicy.decide(withTop(0.05),
                ConfidencePolicy.DEFAULT_STRONG, ConfidencePolicy.DEFAULT_WEAK, true);

        assertThat(d.declined()).isFalse();
        assertThat(d.action()).isEqualTo(ConfidencePolicy.Action.ASK_FOR_BETTER_INPUT);
    }

    @Test
    @DisplayName("The two decline messages say different things")
    void declineMessagesAreDistinct() {
        String unavailable = ConfidencePolicy.declineMessage(ConfidencePolicy.Band.UNAVAILABLE);
        String none = ConfidencePolicy.declineMessage(ConfidencePolicy.Band.NONE);

        assertThat(unavailable).contains("did not complete");
        assertThat(none).contains("clearer");
        assertThat(unavailable).isNotEqualTo(none);
    }

    @Test
    @DisplayName("Custom thresholds are honoured")
    void thresholdsAreConfigurable() {
        // A pipeline advising on something safety-critical should be able to
        // demand more before it will answer plainly.
        assertThat(ConfidencePolicy.decide(withTop(0.80), 0.95, 0.60, false).band())
                .isEqualTo(ConfidencePolicy.Band.MEDIUM);
        assertThat(ConfidencePolicy.decide(withTop(0.50), 0.95, 0.60, false).band())
                .isEqualTo(ConfidencePolicy.Band.WEAK);
    }
}
