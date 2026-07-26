package io.continuum.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private final RateLimiter limiter = new RateLimiter();

    @Test
    @DisplayName("a caller may spend its burst, then is refused")
    void burstThenRefuse() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.take("a", 5, 60).allowed())
                    .as("call %d within burst", i + 1)
                    .isTrue();
        }
        assertThat(limiter.take("a", 5, 60).allowed()).isFalse();
    }

    @Test
    @DisplayName("callers are limited independently")
    void keysAreIndependent() {
        for (int i = 0; i < 5; i++) {
            limiter.take("a", 5, 60);
        }
        assertThat(limiter.take("a", 5, 60).allowed()).isFalse();
        assertThat(limiter.take("b", 5, 60).allowed()).isTrue();
    }

    @Test
    @DisplayName("a refusal says how long to wait, so a client can back off")
    void refusalCarriesRetryAfter() {
        limiter.take("slow", 1, 6);
        RateLimiter.Decision d = limiter.take("slow", 1, 6);

        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterSeconds()).isBetween(1L, 10L);
    }

    @Test
    @DisplayName("the bucket refills over time rather than resetting on a boundary")
    void refillsContinuously() throws Exception {
        // 600/min is 10/s, so ~150ms buys a token back.
        limiter.take("refill", 1, 600);
        assertThat(limiter.take("refill", 1, 600).allowed()).isFalse();

        Thread.sleep(200);

        assertThat(limiter.take("refill", 1, 600).allowed()).isTrue();
    }

    @Test
    @DisplayName("a burst cannot be saved up beyond the bucket's capacity")
    void capacityIsTheCeiling() throws Exception {
        limiter.take("cap", 2, 6000);
        Thread.sleep(150); // long enough to refill far past capacity

        assertThat(limiter.take("cap", 2, 6000).allowed()).isTrue();
        assertThat(limiter.take("cap", 2, 6000).allowed()).isTrue();
        // Only `capacity` tokens can ever accumulate, so the third is refused.
        assertThat(limiter.take("cap", 2, 6000).allowed()).isFalse();
    }
}
