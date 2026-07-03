package io.continuum.autopilot.stats;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BetaDistributionTest {

    @Test
    void meanMatchesCounts() {
        // 8 successes, 2 failures -> mean ~ 9/12 = 0.75 with a Beta(1,1) prior
        BetaDistribution b = BetaDistribution.fromCounts(8, 2);
        assertEquals(9.0 / 12.0, b.mean(), 1e-9);
        assertTrue(b.lowerBound95() < b.mean() && b.mean() < b.upperBound95());
    }

    @Test
    void moreDataNarrowsTheInterval() {
        BetaDistribution few = BetaDistribution.fromCounts(6, 4);
        BetaDistribution many = BetaDistribution.fromCounts(600, 400);
        double widthFew = few.upperBound95() - few.lowerBound95();
        double widthMany = many.upperBound95() - many.lowerBound95();
        assertTrue(widthMany < widthFew, "more evidence should shrink the credible interval");
    }

    @Test
    void samplesAreInUnitInterval() {
        BetaDistribution b = BetaDistribution.fromCounts(5, 5);
        Random rng = new Random(1);
        for (int i = 0; i < 1000; i++) {
            double s = b.sample(rng);
            assertTrue(s >= 0.0 && s <= 1.0);
        }
    }

    @Test
    void sampleMeanApproximatesTrueMean() {
        BetaDistribution b = BetaDistribution.fromCounts(70, 30); // mean ~0.696
        Random rng = new Random(42);
        double sum = 0;
        int n = 20000;
        for (int i = 0; i < n; i++) {
            sum += b.sample(rng);
        }
        assertEquals(b.mean(), sum / n, 0.02);
    }
}
