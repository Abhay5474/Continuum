package io.continuum.cascade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibration exists so the threshold means the same thing on every workload.
 * The two properties that must hold: it declines to calibrate on thin evidence,
 * and the curve it produces never inverts.
 */
class CalibrationStoreTest {

    private final CalibrationStore store = new CalibrationStore();

    @Test
    @DisplayName("with no evidence the raw score passes through untouched")
    void uncalibratedIsIdentity() {
        assertThat(store.calibrate("dev-1", 0.42)).isEqualTo(0.42);
    }

    @Test
    @DisplayName("a bin with too few samples is not trusted")
    void thinBinsAreIgnored() {
        for (int i = 0; i < 3; i++) {
            store.observe("dev-1", 0.85, false);
        }
        // Three observations is not evidence that a 0.85 score means failure.
        assertThat(store.calibrate("dev-1", 0.85)).isEqualTo(0.85);
    }

    @Test
    @DisplayName("a well-observed bin is corrected toward what actually happened")
    void calibratesTowardObservedRate() {
        // A judge score of 0.85 that is only right a quarter of the time is
        // exactly the miscalibration this class exists to fix.
        for (int i = 0; i < 40; i++) {
            store.observe("dev-1", 0.85, i % 4 == 0);
        }

        double calibrated = store.calibrate("dev-1", 0.85);
        assertThat(calibrated).isLessThan(0.5);
        assertThat(calibrated).isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    @DisplayName("the curve never decreases as the raw score rises")
    void curveIsMonotone() {
        // Deliberately inverted evidence: a low bin that succeeds often and a
        // high bin that fails often. Pooling must repair the ordering rather
        // than let a higher score map to a lower probability.
        for (int i = 0; i < 30; i++) {
            store.observe("dev-1", 0.25, true);
            store.observe("dev-1", 0.75, false);
        }

        double low = store.calibrate("dev-1", 0.25);
        double high = store.calibrate("dev-1", 0.75);
        assertThat(high).isGreaterThanOrEqualTo(low);
    }

    @Test
    @DisplayName("tenants are calibrated independently — workloads differ")
    void tenantsAreIndependent() {
        for (int i = 0; i < 40; i++) {
            store.observe("dev-1", 0.85, false);
        }
        assertThat(store.calibrate("dev-1", 0.85)).isLessThan(0.5);
        assertThat(store.calibrate("dev-2", 0.85)).isEqualTo(0.85);
    }

    @Test
    @DisplayName("the profile reports how much evidence backs the curve")
    void profileReportsEvidence() {
        for (int i = 0; i < 20; i++) {
            store.observe("dev-1", 0.55, true);
        }
        Map<String, Object> p = store.profile("dev-1");

        assertThat(p.get("observations")).isEqualTo(20L);
        assertThat(p.get("calibrated")).isEqualTo(true);
        assertThat((List<?>) p.get("curve")).hasSize(10);
    }

    @Test
    @DisplayName("reset clears a tenant back to pass-through")
    void resetClears() {
        for (int i = 0; i < 40; i++) {
            store.observe("dev-1", 0.85, false);
        }
        store.reset("dev-1");

        assertThat(store.calibrate("dev-1", 0.85)).isEqualTo(0.85);
    }
}
