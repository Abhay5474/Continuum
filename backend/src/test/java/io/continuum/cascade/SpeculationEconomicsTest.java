package io.continuum.cascade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dossier that ranked this feature said not to build it until the escalation
 * rate could be measured. These tests check that the advice actually turns on
 * that measurement rather than on enthusiasm.
 */
class SpeculationEconomicsTest {

    @Test
    @DisplayName("At a low escalation rate speculation is called out as mostly waste")
    void lowEscalationIsWasteful() {
        var v = SpeculationEconomics.advise(1000, 100, 0.005, 900, 1.0);

        assertThat(v.worthwhile()).isFalse();
        assertThat(v.summary()).contains("mostly waste");
        // 90% of requests would pay for a strong call they never needed.
        assertThat(v.extraSpendUsd()).isCloseTo(0.9 * 1000 * 0.005,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("At a high escalation rate it becomes the right answer")
    void highEscalationIsWorthwhile() {
        // Most traffic is already paying for both models, just sequentially.
        var v = SpeculationEconomics.advise(1000, 700, 0.005, 900, 5.0);

        assertThat(v.worthwhile()).isTrue();
        assertThat(v.summary()).contains("already paying for both");
        assertThat(v.latencySavedMs()).isCloseTo(0.7 * 900,
                org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("Too little traffic gives no verdict at all, rather than a confident one")
    void thinDataRefusesToAdvise() {
        var v = SpeculationEconomics.advise(5, 4, 0.005, 900, 0.02);

        // 80% escalation would look decisive, and on five requests it is noise.
        assertThat(v.worthwhile()).isFalse();
        assertThat(v.summary()).contains("noise");
        assertThat(v.summary()).contains("guessing");
    }

    @Test
    @DisplayName("Extra spend falls and latency saved rises as escalation climbs")
    void thePairMovesInOppositeDirections() {
        var low = SpeculationEconomics.advise(1000, 200, 0.005, 900, 1.0);
        var high = SpeculationEconomics.advise(1000, 800, 0.005, 900, 1.0);

        assertThat(high.extraSpendUsd()).isLessThan(low.extraSpendUsd());
        assertThat(high.latencySavedMs()).isGreaterThan(low.latencySavedMs());
    }

    @Test
    @DisplayName("With no recorded spend the percentage is absent, not zero")
    void unknownShareIsNotZero() {
        // Reporting "0% more expensive" when the baseline is unknown would be
        // the most flattering possible lie.
        var v = SpeculationEconomics.advise(100, 10, 0.005, 900, 0.0);

        assertThat(Double.isNaN(v.extraSpendPct())).isTrue();
        assertThat(v.describe().get("extraSpendPct")).isNull();
        assertThat(v.summary()).contains("share unknown");
    }
}
