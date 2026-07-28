package io.continuum.specialist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The detector exists so the policy page reports an observation rather than its
 * own intent. These tests pin what it catches — and, just as deliberately, what
 * it admits it cannot.
 */
class HedgeDetectorTest {

    @Test
    @DisplayName("A flat answer to a hedge instruction is caught")
    void flatAnswerFailsTheHedge() {
        // This is the failure the whole class exists for: the policy asked for
        // uncertainty and got none.
        HedgeDetector.Compliance c = HedgeDetector.check(ConfidencePolicy.Action.HEDGE,
                "This is a deep laceration. Apply a tourniquet above the wound immediately.");

        assertThat(c.checked()).isTrue();
        assertThat(c.complied()).isFalse();
        assertThat(c.markers()).isEmpty();
        assertThat(c.assertions()).contains("this is a");
    }

    @Test
    @DisplayName("A hedged answer is recognised, and the markers are shown")
    void hedgedAnswerPasses() {
        HedgeDetector.Compliance c = HedgeDetector.check(ConfidencePolicy.Action.HEDGE,
                "This may be an open wound, though the image is not conclusive. "
                        + "A closer photo would help confirm it.");

        assertThat(c.complied()).isTrue();
        // The markers are returned so a developer can judge the call rather than
        // trust a boolean.
        assertThat(c.markers()).contains("may", "not conclusive");
    }

    @Test
    @DisplayName("Asking for better input is recognised")
    void refusalIsRecognised() {
        HedgeDetector.Compliance c = HedgeDetector.check(
                ConfidencePolicy.Action.ASK_FOR_BETTER_INPUT,
                "I cannot assess this from the image provided. A clearer, closer photo of the "
                        + "affected area would be needed.");

        assertThat(c.complied()).isTrue();
        assertThat(c.markers()).contains("cannot assess", "clearer");
    }

    @Test
    @DisplayName("An answer that advises anyway fails the refusal check")
    void advisingAnywayFailsTheRefusal() {
        HedgeDetector.Compliance c = HedgeDetector.check(
                ConfidencePolicy.Action.ASK_FOR_BETTER_INPUT,
                "The animal has a fractured leg. Splint it and keep the animal still.");

        assertThat(c.complied()).isFalse();
        assertThat(c.assertions()).contains("the animal has");
    }

    // --- what it refuses to claim ---------------------------------------------

    @Test
    @DisplayName("PASS is recorded as not checked, never as complied")
    void passIsNotCountedAsCompliance() {
        // Counting untested runs as successes would inflate the compliance rate
        // with every strong-evidence answer — the ones nothing was asked of.
        HedgeDetector.Compliance c =
                HedgeDetector.check(ConfidencePolicy.Action.PASS, "Apply firm pressure.");

        assertThat(c.checked()).isFalse();
        assertThat(c.complied()).isFalse();
        assertThat(c.method()).isEqualTo("not applicable");
    }

    @Test
    @DisplayName("A declined run has no model answer to check")
    void declineIsNotChecked() {
        assertThat(HedgeDetector.check(ConfidencePolicy.Action.DECLINE, "anything").checked())
                .isFalse();
    }

    @Test
    @DisplayName("The method is reported as lexical, not as judgement")
    void methodIsDisclosed() {
        assertThat(HedgeDetector.check(ConfidencePolicy.Action.HEDGE, "this may be a wound").method())
                .isEqualTo("lexical");
    }

    @Test
    @DisplayName("A decorative hedge passes, and that limit is deliberate")
    void decorativeHedgeIsNotCaught() {
        // Documented rather than papered over: a word list cannot tell a real
        // hedge from a word stuck in front of confident advice. Claiming to
        // would repeat the overreach this class exists to prevent.
        HedgeDetector.Compliance c = HedgeDetector.check(ConfidencePolicy.Action.HEDGE,
                "This may possibly be an open wound. Apply a tourniquet immediately.");

        assertThat(c.complied()).isTrue();
    }

    @Test
    @DisplayName("An empty or missing answer never counts as compliance")
    void emptyAnswerFails() {
        assertThat(HedgeDetector.check(ConfidencePolicy.Action.HEDGE, null).complied()).isFalse();
        assertThat(HedgeDetector.check(ConfidencePolicy.Action.HEDGE, "").complied()).isFalse();
    }

    @Test
    @DisplayName("Detection is case-insensitive")
    void caseDoesNotMatter() {
        assertThat(HedgeDetector.check(ConfidencePolicy.Action.HEDGE,
                "This MIGHT be a wound; it is UNCLEAR.").complied()).isTrue();
    }
}
