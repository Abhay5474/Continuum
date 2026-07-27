package io.continuum.quality;

import io.continuum.cascade.DeferralJudge;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import io.continuum.uncertainty.AnswerClusterer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate's job is to catch objectively-checkable defects and, just as
 * importantly, to stay quiet otherwise. A gate that cries wolf gets turned off,
 * so the false-positive cases here matter as much as the true ones.
 */
class QualityGateTest {

    private final QualityGate gate = new QualityGate(new DeferralJudge(), new AnswerClusterer());

    private static final double THRESHOLD = 0.6;

    private static LlmRequest ask(String prompt) {
        return new LlmRequest("m", List.of(Message.user(prompt)), 512, 0.2);
    }

    private static LlmRequest withContext(String context, String prompt) {
        return new LlmRequest("m", List.of(Message.system(context), Message.user(prompt)), 512, 0.2);
    }

    @Test
    @DisplayName("a good answer passes and is not annotated with defects")
    void goodAnswerPasses() {
        var v = gate.check(
                ask("What is the refund window for annual plans?"),
                "Annual plans carry a 30-day refund window measured from the renewal date.",
                0.3, THRESHOLD);

        assertThat(v.action()).isEqualTo(QualityGate.Action.PASS);
        assertThat(v.defects()).isEmpty();
    }

    @Test
    @DisplayName("a broken format contract is a repairable defect")
    void formatBreachRepairs() {
        var v = gate.check(
                ask("Return the customer record as valid JSON with keys name and total."),
                "The customer is Acme Ltd and the total is 4200 pounds.", 0.2, THRESHOLD);

        assertThat(v.action()).isEqualTo(QualityGate.Action.REPAIR);
        assertThat(v.summary()).contains("JSON");
    }

    @Test
    @DisplayName("a refusal is blocked, not repaired — asking again buys the same refusal twice")
    void refusalIsBlocked() {
        var v = gate.check(ask("Help me with this."),
                "I cannot help with that request.", 0.2, THRESHOLD);

        assertThat(v.action()).isEqualTo(QualityGate.Action.BLOCK);
        assertThat(v.defects()).anyMatch(d -> d.contains("refused"));
    }

    @Test
    @DisplayName("a three-part question answered in one part is incomplete")
    void multiPartIsChecked() {
        var v = gate.check(
                ask("What is the refund window? How do I cancel my subscription? "
                        + "Which payment methods does the platform accept?"),
                "The refund window is 30 days from renewal.", 0.4, THRESHOLD);

        assertThat(v.defects()).anyMatch(d -> d.contains("questions"));
    }

    @Test
    @DisplayName("a single-question request never trips the completeness check")
    void singleQuestionIsNotPenalised() {
        var v = gate.check(ask("What is the refund window?"),
                "The refund window is 30 days from renewal.", 0.2, THRESHOLD);

        assertThat(v.defects()).noneMatch(d -> d.contains("questions"));
    }

    @Test
    @DisplayName("figures invented out of nowhere are caught against supplied context")
    void ungroundedFiguresAreCaught() {
        String context = "Contract summary. The agreement commenced on 1 January 2024 and runs "
                + "for 36 months. The annual fee is 40000 pounds, payable quarterly in advance. "
                + "Either party may terminate on 90 days written notice after the first 12 months. "
                + "Late payment attracts interest at 4 percent above base rate.";
        var v = gate.check(withContext(context, "Summarise the commercial terms."),
                "The agreement runs for 60 months at 75000 per year with 15 days notice.",
                0.4, THRESHOLD);

        assertThat(v.defects()).anyMatch(d -> d.contains("do not appear in the supplied context"));
    }

    @Test
    @DisplayName("figures that come from the context are not flagged")
    void groundedFiguresPass() {
        String context = "Contract summary. The agreement commenced on 1 January 2024 and runs "
                + "for 36 months. The annual fee is 40000 pounds, payable quarterly in advance. "
                + "Either party may terminate on 90 days written notice after the first 12 months.";
        var v = gate.check(withContext(context, "Summarise the commercial terms."),
                "The term is 36 months at 40000 per year, terminable on 90 days notice.",
                0.4, THRESHOLD);

        assertThat(v.defects()).noneMatch(d -> d.contains("supplied context"));
    }

    @Test
    @DisplayName("grounding does not run without substantial context — a short prompt is not evidence")
    void groundingNeedsContext() {
        var v = gate.check(ask("How many days?"),
                "It is 45 days, or 90 days for enterprise, or 120 days on request.", 0.2, THRESHOLD);

        assertThat(v.defects()).noneMatch(d -> d.contains("supplied context"));
    }

    @Test
    @DisplayName("an answer to a different question fails relevance")
    void irrelevantAnswerFails() {
        var v = gate.check(ask("What is the refund window for annual subscription plans?"),
                "Photosynthesis converts light energy into chemical energy in plants.",
                0.3, THRESHOLD);

        assertThat(v.action()).isEqualTo(QualityGate.Action.REPAIR);
    }

    @Test
    @DisplayName("an empty answer is repairable rather than silently returned")
    void emptyAnswerRepairs() {
        assertThat(gate.check(ask("anything"), "", 0.3, THRESHOLD).action())
                .isEqualTo(QualityGate.Action.REPAIR);
        assertThat(gate.check(ask("anything"), null, 0.3, THRESHOLD).action())
                .isEqualTo(QualityGate.Action.REPAIR);
    }

    @Test
    @DisplayName("the repair instruction names the actual defects, not a vague complaint")
    void repairInstructionIsSpecific() {
        var v = gate.check(
                ask("Return the customer record as valid JSON with keys name and total."),
                "The customer is Acme Ltd and the total is 4200 pounds.", 0.2, THRESHOLD);

        // A vague complaint produces a vague correction; the model needs to be
        // told what was wrong.
        assertThat(v.repairInstruction()).contains("JSON");
        assertThat(v.repairInstruction()).contains("corrected answer");
    }

    @Test
    @DisplayName("raising the threshold turns a marginal pass into a repair")
    void thresholdGovernsAction() {
        String prompt = "What is the refund window? How do I cancel?";
        String answer = "The refund window is 30 days from renewal.";

        assertThat(gate.check(ask(prompt), answer, 0.3, 0.2).action())
                .isEqualTo(QualityGate.Action.PASS);
        assertThat(gate.check(ask(prompt), answer, 0.3, 0.95).action())
                .isEqualTo(QualityGate.Action.REPAIR);
    }

    @Test
    @DisplayName("every dimension is reported, whether it failed or not")
    void allDimensionsReported() {
        var v = gate.check(ask("What is the refund window?"), "Thirty days.", 0.2, THRESHOLD);

        assertThat(QualityGate.scores(v)).containsKeys(
                "adherence", "completeness", "grounding", "relevance");
    }
}
