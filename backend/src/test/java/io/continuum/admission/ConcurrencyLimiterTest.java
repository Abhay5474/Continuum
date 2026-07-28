package io.continuum.admission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limiter's job is to find a number nobody knows. The hard half is not
 * finding it — it is refusing to invent one: staying put when the system is
 * idle, and coming down fast when it is not.
 */
class ConcurrencyLimiterTest {

    /** Drives n successful calls at a given latency and saturation. */
    private static void drive(ConcurrencyLimiter l, int n, double rttMs) {
        for (int i = 0; i < n; i++) {
            l.onSuccess(rttMs, l.limit()); // fully saturated
        }
    }

    @Test
    @DisplayName("A fresh limiter starts conservative, not unlimited")
    void startsConservative() {
        ConcurrencyLimiter l = new ConcurrencyLimiter();

        assertThat(l.limit()).isEqualTo((int) ConcurrencyLimiter.INITIAL);
        assertThat(l.samples()).isZero();
    }

    @Test
    @DisplayName("Steady low latency grows the limit")
    void steadyLatencyGrows() {
        ConcurrencyLimiter l = new ConcurrencyLimiter();
        int before = l.limit();

        drive(l, 40, 100);

        assertThat(l.limit()).isGreaterThan(before);
        assertThat(l.minRttMs()).isCloseTo(100, org.assertj.core.data.Offset.offset(2.0));
    }

    @Test
    @DisplayName("Latency rising above the baseline pulls the limit back")
    void congestionShrinks() {
        ConcurrencyLimiter l = new ConcurrencyLimiter();
        drive(l, 40, 100);
        int atSteady = l.limit();

        // The whole idea: latency climbs, nothing has failed yet, and the limit
        // comes down before the provider starts refusing.
        drive(l, 40, 400);

        assertThat(l.limit()).isLessThan(atSteady);
        assertThat(l.drops()).isZero();
    }

    @Test
    @DisplayName("The limit never grows while the system is idle")
    void doesNotGrowWhenIdle() {
        ConcurrencyLimiter l = new ConcurrencyLimiter();
        int before = l.limit();

        // Fast responses, but only one request in flight. This is not evidence
        // of capacity; without the guard the limit ratchets up during quiet
        // periods and the first real burst discovers it was fiction.
        for (int i = 0; i < 100; i++) {
            l.onSuccess(50, 1);
        }

        assertThat(l.limit()).isEqualTo(before);
    }

    @Test
    @DisplayName("A drop cuts the limit immediately")
    void dropBacksOffAtOnce() {
        ConcurrencyLimiter l = new ConcurrencyLimiter();
        drive(l, 60, 100);
        int grown = l.limit();

        l.onDrop();

        assertThat(l.limit()).isLessThan(grown);
        assertThat(l.drops()).isEqualTo(1);
    }

    @Test
    @DisplayName("No single sample can more than halve the limit")
    void singleSampleCannotCollapseIt() {
        ConcurrencyLimiter l = new ConcurrencyLimiter();
        drive(l, 60, 100);
        int grown = l.limit();

        // One pathological response — a cold start, a retry behind the scenes —
        // must not throw away everything learned.
        l.onSuccess(60_000, grown);

        assertThat(l.limit()).isGreaterThanOrEqualTo(grown / 2);
    }

    @Test
    @DisplayName("Repeated congestion does collapse it, and it recovers after")
    void sustainedCongestionCollapsesThenRecovers() {
        ConcurrencyLimiter l = new ConcurrencyLimiter();
        drive(l, 60, 100);
        int grown = l.limit();

        drive(l, 60, 5_000);
        int congested = l.limit();
        assertThat(congested).isLessThan(grown / 2);

        // And when the provider recovers, so does the limit.
        drive(l, 80, 100);
        assertThat(l.limit()).isGreaterThan(congested);
    }

    @Test
    @DisplayName("The limit stays within its bounds under any input")
    void staysWithinBounds() {
        ConcurrencyLimiter l = new ConcurrencyLimiter();

        for (int i = 0; i < 500; i++) {
            l.onSuccess(1, 1000);
        }
        assertThat(l.limit()).isLessThanOrEqualTo((int) ConcurrencyLimiter.MAX);

        for (int i = 0; i < 500; i++) {
            l.onDrop();
        }
        assertThat(l.limit()).isGreaterThanOrEqualTo((int) ConcurrencyLimiter.MIN);
    }

    @Test
    @DisplayName("The latency baseline drifts up rather than freezing forever")
    void baselineDecays() {
        ConcurrencyLimiter l = new ConcurrencyLimiter();
        // One unusually fast response, then a stable slower reality.
        l.onSuccess(10, 8);
        drive(l, 400, 200);

        // Held forever, 10ms would make every 200ms response look 20x congested
        // and the limit would never recover.
        assertThat(l.minRttMs()).isGreaterThan(10);
        assertThat(l.minRttMs()).isLessThanOrEqualTo(200);
    }

    @Test
    @DisplayName("Nonsense latency is ignored rather than poisoning the estimate")
    void ignoresNonsense() {
        ConcurrencyLimiter l = new ConcurrencyLimiter();
        int before = l.limit();

        l.onSuccess(0, 5);
        l.onSuccess(-100, 5);

        assertThat(l.limit()).isEqualTo(before);
        assertThat(l.samples()).isZero();
    }


    @Test
    @DisplayName("A fast provider is not throttled by measurement noise")
    void noiseFloorProtectsFastProviders() {
        // Found by driving 120 concurrent requests at a provider answering in
        // 3ms. Queueing added 3ms, the raw ratio read 0.48, and the limit
        // collapsed to 4 on a provider that was not congested at all. At those
        // timescales the difference is thread scheduling, not a queue.
        ConcurrencyLimiter l = new ConcurrencyLimiter();
        drive(l, 40, 3);
        int steady = l.limit();

        drive(l, 40, 6); // "doubled" latency — 3ms of jitter

        assertThat(l.limit()).isGreaterThanOrEqualTo(steady);
    }

    @Test
    @DisplayName("A slow provider still shows real congestion")
    void noiseFloorDoesNotMaskRealCongestion() {
        // The floor must not blunt the signal where it matters: hosted models
        // sit at hundreds of milliseconds, well above it.
        assertThat(ConcurrencyLimiter.gradient(200, 400)).isEqualTo(0.5);
        assertThat(ConcurrencyLimiter.gradient(3, 6)).isEqualTo(1.0);
        // And a fast baseline against a genuinely slow response still signals.
        assertThat(ConcurrencyLimiter.gradient(3, 400)).isLessThan(0.1);
    }

    @Test
    @DisplayName("The readout reports the gradient the decision was made on")
    void describesItself() {
        ConcurrencyLimiter l = new ConcurrencyLimiter();
        drive(l, 20, 100);
        l.onSuccess(200, l.limit());

        var d = l.describe();
        assertThat(d).containsKeys("limit", "minRttMs", "lastRttMs", "gradient", "samples", "drops");
        // ~100/200: the number that actually drove the adjustment.
        assertThat((double) d.get("gradient")).isBetween(0.4, 0.6);
    }
}
