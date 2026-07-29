package io.continuum.counterfactual;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evaluator's job is not to produce a number — it is to say how much of the
 * number is measured. These tests are mostly about that distinction.
 */
class CounterfactualEvaluatorTest {

    private static CounterfactualEvaluator.Observation obs(String arm, double complexity,
                                                           int tokens, double cost) {
        String[] p = arm.split("/");
        return new CounterfactualEvaluator.Observation(p[0], p[1], complexity, 100, tokens, cost, true);
    }

    private static List<CounterfactualEvaluator.Observation> log() {
        List<CounterfactualEvaluator.Observation> out = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            out.add(obs("mock/mock-small", 0.2, 100, 0.0001));
        }
        for (int i = 0; i < 10; i++) {
            out.add(obs("mock/mock-large", 0.8, 100, 0.0050));
        }
        return out;
    }

    @Test
    @DisplayName("A candidate that agrees everywhere is measured, not estimated")
    void fullAgreementIsMeasured() {
        // It picks exactly what ran, so the logged outcome IS the counterfactual
        // outcome and no model is involved anywhere.
        var report = CounterfactualEvaluator.evaluate(log(),
                CounterfactualEvaluator.threshold(0.5, "mock/mock-small", "mock/mock-large"));

        assertThat(report.agreedFraction()).isEqualTo(1.0);
        assertThat(report.estimatedCost()).isEqualTo(report.actualCost());
        assertThat(report.caveat()).contains("what happened, not an estimate");
        assertThat(report.lines()).allMatch(CounterfactualEvaluator.Line::confident);
    }

    @Test
    @DisplayName("Routing everything to the cheap arm shows a saving, and says half is modelled")
    void divergenceIsReportedAsModelled() {
        var report = CounterfactualEvaluator.evaluate(log(),
                CounterfactualEvaluator.always("mock/mock-small"));

        // The ten large-model requests would have gone cheap instead.
        assertThat(report.estimatedCost()).isLessThan(report.actualCost());
        assertThat(report.agreedFraction()).isEqualTo(0.5);
        assertThat(report.caveat()).contains("no propensity correction");
    }

    @Test
    @DisplayName("An arm with no history at all is counted, not silently invented")
    void unknownArmIsNamed() {
        // Nothing ever ran on this arm, so there is no evidence to estimate
        // from. Producing a confident number here is the classic failure of
        // off-policy evaluation.
        var report = CounterfactualEvaluator.evaluate(log(),
                CounterfactualEvaluator.always("openai/gpt-9"));

        assertThat(report.unmodellable()).isEqualTo(20);
        assertThat(report.agreedFraction()).isZero();
        assertThat(report.caveat()).contains("no comparable history");
        assertThat(report.caveat()).contains("direction, not a number");
    }

    @Test
    @DisplayName("Each arm's estimate uses that arm's own history, not the global average")
    void estimatesArePerArm() {
        // Crediting the cheap arm with the expensive arm's cost profile would
        // wipe out the very saving the evaluation exists to find.
        var report = CounterfactualEvaluator.evaluate(log(),
                CounterfactualEvaluator.always("mock/mock-small"));

        // 20 requests × 100 tokens at the cheap arm's rate of 1e-6/token.
        assertThat(report.estimatedCost()).isCloseTo(0.002, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("Measured and modelled totals are reported separately, never blended away")
    void measuredAndModelledAreSeparable() {
        var report = CounterfactualEvaluator.evaluate(log(),
                CounterfactualEvaluator.always("mock/mock-small"));

        assertThat(report.measuredCandidateCost()).isGreaterThan(0);
        assertThat(report.modelledCandidateCost()).isGreaterThan(0);
        assertThat(report.measuredCandidateCost() + report.modelledCandidateCost())
                .isCloseTo(report.estimatedCost(), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("An empty log yields no estimate and says so plainly")
    void emptyLog() {
        var report = CounterfactualEvaluator.evaluate(List.of(),
                CounterfactualEvaluator.always("mock/mock-small"));

        assertThat(report.requests()).isZero();
        assertThat(report.caveat()).contains("no estimate");
    }
}
