package io.continuum.aichaos;

import io.continuum.aichaos.injectors.DegradedAnswerInjector;
import io.continuum.aichaos.injectors.HallucinationInjector;
import io.continuum.cascade.DeferralJudge;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import io.continuum.quality.QualityGate;
import io.continuum.uncertainty.AnswerClusterer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The semantic breaker learns from quality scores, so a drill meant to trip it
 * has to produce answers the quality gate scores low. A hallucination does not:
 * it is well formed and on topic, and scores like a good answer.
 */
class DegradedAnswerInjectorTest {

    private final QualityGate gate = new QualityGate(new DeferralJudge(), new AnswerClusterer());
    private final LlmRequest question = LlmRequest.of(List.of(Message.user(
            "My order of a pressure cooker arrived with a dented lid. Can I get a replacement?")));
    private final String good = "Yes. Since the lid arrived dented, you can request a replacement "
            + "from your orders page within 7 days of delivery, and the courier will collect the damaged cooker.";

    @Test
    void aDegradedAnswerScoresFarBelowTheAnswerItReplaced() {
        double before = gate.check(question, good, 0.3, 0.7).score();
        String degraded = new DegradedAnswerInjector().degrade(good);
        double after = gate.check(question, degraded, 0.3, 0.7).score();

        assertThat(before).isGreaterThan(0.8);
        assertThat(after).isLessThan(0.5);
        assertThat(gate.check(question, degraded, 0.3, 0.7).action()).isEqualTo(QualityGate.Action.REPAIR);
    }

    @Test
    void aHallucinationIsInvisibleToTheScore() {
        // Why the breaker needed this drill at all.
        double score = gate.check(question, new HallucinationInjector().corrupt(good), 0.3, 0.7).score();
        assertThat(score).isGreaterThan(0.8);
    }

    @Test
    void nothingToDegradeStaysNothing() {
        assertThat(new DegradedAnswerInjector().degrade(null)).isNull();
    }
}
