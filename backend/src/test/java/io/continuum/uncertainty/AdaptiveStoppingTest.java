package io.continuum.uncertainty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The stopping rule's value is entirely in when it refuses to stop. Stopping
 * early on agreement is easy; the tests that matter are the ones where it keeps
 * paying because the answer genuinely is not decided.
 */
class AdaptiveStoppingTest {

    private static AdaptiveStopping.Decision decide(List<Integer> clusters, int budget) {
        int drawn = clusters.stream().mapToInt(Integer::intValue).sum();
        return AdaptiveStopping.decide(clusters, drawn, budget, 0.05);
    }

    // --- the posterior --------------------------------------------------------

    @Test
    @DisplayName("Two out of two is not yet decisive")
    void twoAgreeingSamplesAreNotProof() {
        // Beta(3,1) still puts real mass below 0.5. Two coin flips landing the
        // same way is not evidence the coin is biased, and treating it as such
        // is how an adaptive rule becomes a cheaper way to be wrong.
        double p = AdaptiveStopping.probabilityLeaderIsNotMajority(2, 0);

        assertThat(p).isCloseTo(0.125, within(0.001));
        assertThat(decide(List.of(2), 5).stop()).isFalse();
    }

    @Test
    @DisplayName("Unanimous agreement becomes decisive only when it has earned it")
    void unanimityEventuallyStops() {
        // Three in a row gives 0.5^4 = 0.0625 — still above a 5% bar, so it
        // keeps paying. Four gives 0.03125 and stops. The rule is not "everyone
        // agreed", it is "agreement this consistent is unlikely to reverse".
        assertThat(AdaptiveStopping.probabilityLeaderIsNotMajority(3, 0))
                .isCloseTo(0.0625, within(0.0005));
        assertThat(decide(List.of(3), 7).stop()).isFalse();

        assertThat(AdaptiveStopping.probabilityLeaderIsNotMajority(4, 0))
                .isCloseTo(0.03125, within(0.0005));
        assertThat(decide(List.of(4), 7).stop()).isTrue();
    }

    @Test
    @DisplayName("A split vote never stops early, however many samples are drawn")
    void disagreementKeepsSampling() {
        // The case the extra samples exist for. An adaptive rule that shortened
        // this would be saving money on exactly the questions worth spending it
        // on.
        assertThat(decide(List.of(2, 2), 8).stop()).isFalse();
        assertThat(decide(List.of(3, 3), 8).stop()).isFalse();
        assertThat(decide(List.of(3, 2, 1), 8).stop()).isFalse();
    }

    @Test
    @DisplayName("A near-tie is not treated as a majority")
    void narrowLeadIsNotDecisive() {
        assertThat(decide(List.of(4, 3), 10).stop()).isFalse();
        assertThat(decide(List.of(5, 4), 12).stop()).isFalse();
    }

    @Test
    @DisplayName("A strong majority over a minority does stop")
    void clearMajorityStops() {
        AdaptiveStopping.Decision d = decide(List.of(7, 1), 12);

        assertThat(d.stop()).isTrue();
        assertThat(d.overturn()).isLessThan(0.05);
        assertThat(d.reason()).contains("7 of 8 samples agree");
    }

    // --- the guards -----------------------------------------------------------

    @Test
    @DisplayName("It never stops below the minimum, whatever the posterior says")
    void neverStopsTooEarly() {
        assertThat(AdaptiveStopping.MIN_SAMPLES).isGreaterThanOrEqualTo(2);
        assertThat(AdaptiveStopping.decide(List.of(1), 1, 5, 0.05).stop()).isFalse();
        assertThat(AdaptiveStopping.decide(List.of(1), 1, 5, 0.99).stop()).isFalse();
    }

    @Test
    @DisplayName("The configured budget is still a hard ceiling")
    void budgetIsRespected() {
        // Adaptive means fewer samples, never more. A rule that could exceed the
        // budget would turn an opt-in cost into an unbounded one.
        AdaptiveStopping.Decision d = AdaptiveStopping.decide(List.of(2, 2), 4, 4, 0.001);

        assertThat(d.stop()).isTrue();
        assertThat(d.reason()).contains("budget");
    }

    @Test
    @DisplayName("A stricter threshold buys more samples, a looser one fewer")
    void thresholdMovesTheDecision() {
        List<Integer> clusters = List.of(3);
        assertThat(AdaptiveStopping.decide(clusters, 3, 9, 0.20).stop()).isTrue();
        assertThat(AdaptiveStopping.decide(clusters, 3, 9, 0.01).stop()).isFalse();
    }

    @Test
    @DisplayName("Every decision explains itself in samples, not in probabilities alone")
    void reasonsAreReadable() {
        AdaptiveStopping.Decision d = decide(List.of(5, 1), 10);

        assertThat(d.reason()).contains("5 of 6 samples agree").contains("%");
        assertThat(d.describe()).containsKeys("stop", "overturnProbability", "drawn", "leader", "reason");
    }

    @Test
    @DisplayName("The Beta CDF matches known values")
    void posteriorIsCorrect() {
        // Beta(a+1,b+1) at 0.5, checked against the closed forms.
        assertThat(AdaptiveStopping.probabilityLeaderIsNotMajority(1, 1))
                .isCloseTo(0.5, within(1e-6));
        assertThat(AdaptiveStopping.probabilityLeaderIsNotMajority(3, 0))
                .isCloseTo(0.0625, within(1e-6));
        assertThat(AdaptiveStopping.probabilityLeaderIsNotMajority(0, 3))
                .isCloseTo(0.9375, within(1e-6));
    }

    @Test
    @DisplayName("Empty input is handled rather than dividing by nothing")
    void emptyInput() {
        assertThat(AdaptiveStopping.decide(List.of(), 0, 5, 0.05).stop()).isFalse();
        assertThat(AdaptiveStopping.decide(null, 0, 5, 0.05).stop()).isFalse();
    }
}
