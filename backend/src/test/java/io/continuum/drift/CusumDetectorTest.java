package io.continuum.drift;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The detector has two jobs and the second one is harder: notice a real,
 * sustained decline, and stay silent through ordinary noise. A drift detector
 * that fires on variance is worse than none — it teaches its operator to ignore
 * it.
 */
class CusumDetectorTest {

    @Test
    @DisplayName("nothing fires before the baseline is learned")
    void warmupNeverFires() {
        var d = new CusumDetector(30, 0.05, 0.75);
        // Catastrophic input from the very first sample. Twenty-nine of them are
        // still not a baseline, so there is nothing to have drifted from.
        for (int i = 0; i < 29; i++) {
            assertThat(d.observe(0.0)).isEqualTo(CusumDetector.State.WARMING);
        }
        assertThat(d.warm()).isFalse();
    }

    @Test
    @DisplayName("a steady stream at the baseline stays stable")
    void steadySignalIsStable() {
        var d = CusumDetector.forUnitSignal();
        for (int i = 0; i < 200; i++) {
            d.observe(0.9);
        }
        assertThat(d.accumulated()).isZero();
        assertThat(d.pressure()).isZero();
    }

    @Test
    @DisplayName("realistic noise around a stable mean does not trip it")
    void noiseDoesNotTrip() {
        var d = CusumDetector.forUnitSignal();
        Random rng = new Random(42);
        CusumDetector.State worst = CusumDetector.State.WARMING;
        for (int i = 0; i < 500; i++) {
            // Mean 0.85, standard deviation ~0.04 — ordinary variation in a
            // quality score, and none of it is an incident.
            double v = Math.max(0, Math.min(1, 0.85 + rng.nextGaussian() * 0.04));
            var s = d.observe(v);
            if (s == CusumDetector.State.DRIFTED) {
                worst = s;
            }
        }
        assertThat(worst).isNotEqualTo(CusumDetector.State.DRIFTED);
    }

    @Test
    @DisplayName("a sustained decline is caught")
    void sustainedDeclineTrips() {
        var d = CusumDetector.forUnitSignal();
        for (int i = 0; i < 60; i++) {
            d.observe(0.9);
        }
        CusumDetector.State state = CusumDetector.State.STABLE;
        for (int i = 0; i < 40 && state != CusumDetector.State.DRIFTED; i++) {
            state = d.observe(0.55);
        }
        assertThat(state).isEqualTo(CusumDetector.State.DRIFTED);
    }

    @Test
    @DisplayName("a brief dip decays back instead of accumulating toward a trip")
    void transientDipRecovers() {
        var d = CusumDetector.forUnitSignal();
        for (int i = 0; i < 60; i++) {
            d.observe(0.9);
        }
        // Two bad requests happen. That is a blip, not a regression.
        for (int i = 0; i < 2; i++) {
            d.observe(0.3);
        }
        double afterDip = d.accumulated();
        assertThat(afterDip).isGreaterThan(0);
        assertThat(afterDip).isLessThan(d.threshold());

        for (int i = 0; i < 30; i++) {
            d.observe(0.9);
        }
        // The accumulator is floored at zero, so recovery genuinely clears it
        // rather than leaving a debt that a later blip inherits.
        assertThat(d.accumulated()).isZero();
    }

    @Test
    @DisplayName("a small persistent deficit still trips — that is the point of CUSUM")
    void smallPersistentShiftTrips() {
        var d = CusumDetector.forUnitSignal();
        for (int i = 0; i < 60; i++) {
            d.observe(0.90);
        }
        // 0.10 below baseline is half of what the "sustained decline" test used
        // and would not cross any fixed threshold worth setting. Persistence is
        // what makes it detectable.
        CusumDetector.State state = CusumDetector.State.STABLE;
        for (int i = 0; i < 200 && state != CusumDetector.State.DRIFTED; i++) {
            state = d.observe(0.80);
        }
        assertThat(state).isEqualTo(CusumDetector.State.DRIFTED);
    }

    @Test
    @DisplayName("one catastrophic answer cannot trip it on its own")
    void singleOutlierDoesNotTrip() {
        var d = CusumDetector.forUnitSignal();
        for (int i = 0; i < 60; i++) {
            d.observe(0.9);
        }
        // Without a cap on per-observation contribution, a shortfall of 0.85
        // clears the 0.75 threshold immediately — which would make this a
        // threshold alarm wearing a CUSUM costume.
        assertThat(d.observe(0.0)).isNotEqualTo(CusumDetector.State.DRIFTED);
        assertThat(d.observe(0.0)).isNotEqualTo(CusumDetector.State.DRIFTED);
        // Three consecutive is a pattern, and it should fire.
        assertThat(d.observe(0.0)).isEqualTo(CusumDetector.State.DRIFTED);
    }

    @Test
    @DisplayName("improvement is never treated as an incident")
    void improvementIsIgnored() {
        var d = CusumDetector.forUnitSignal();
        for (int i = 0; i < 60; i++) {
            d.observe(0.6);
        }
        for (int i = 0; i < 100; i++) {
            assertThat(d.observe(0.99)).isNotEqualTo(CusumDetector.State.DRIFTED);
        }
        assertThat(d.accumulated()).isZero();
    }

    @Test
    @DisplayName("the baseline freezes after warm-up, so degradation never becomes the new normal")
    void baselineDoesNotDrift() {
        var d = CusumDetector.forUnitSignal();
        for (int i = 0; i < 40; i++) {
            d.observe(0.9);
        }
        double learned = d.baseline();
        for (int i = 0; i < 200; i++) {
            d.observe(0.4);
        }
        // A monitor that keeps updating its baseline from degraded data learns
        // to accept the degradation and goes quiet.
        assertThat(d.baseline()).isEqualTo(learned);
    }

    @Test
    @DisplayName("pressure reports how close it is before it fires")
    void pressureIsReported() {
        var d = CusumDetector.forUnitSignal();
        for (int i = 0; i < 60; i++) {
            d.observe(0.9);
        }
        assertThat(d.pressure()).isZero();
        d.observe(0.5);
        assertThat(d.pressure()).isStrictlyBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("reset clears the accumulator but keeps the baseline")
    void resetKeepsBaseline() {
        var d = CusumDetector.forUnitSignal();
        for (int i = 0; i < 60; i++) {
            d.observe(0.9);
        }
        double learned = d.baseline();
        d.observe(0.2);
        d.reset();

        assertThat(d.accumulated()).isZero();
        assertThat(d.baseline()).isEqualTo(learned);
    }

    @Test
    @DisplayName("rebaseline forgets everything, for a model being relearned")
    void rebaselineForgets() {
        var d = CusumDetector.forUnitSignal();
        for (int i = 0; i < 60; i++) {
            d.observe(0.9);
        }
        d.rebaseline();

        assertThat(d.warm()).isFalse();
        assertThat(d.count()).isZero();
        assertThat(d.observe(0.1)).isEqualTo(CusumDetector.State.WARMING);
    }
}
