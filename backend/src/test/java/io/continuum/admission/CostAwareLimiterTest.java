package io.continuum.admission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The point of this limiter is that a big request costs more of the allowance
 * than a small one. Most of these tests check that, and that the accounting
 * cannot leak.
 */
class CostAwareLimiterTest {

    private static final int REQS = 1000;   // deliberately not the binding limit
    private static final int TOKENS = 10_000;

    private CostAwareLimiter.Outcome ask(CostAwareLimiter l, String who, int prompt, Integer max) {
        return l.reserve(who, prompt, max, REQS, TOKENS);
    }

    @Test
    @DisplayName("A large request consumes more allowance than a small one")
    void largeRequestsCostMore() {
        var limiter = new CostAwareLimiter();

        var small = ask(limiter, "a", 10, 10);
        var large = ask(limiter, "b", 5000, 1000);

        assertThat(small.decision().reservedTokens())
                .isLessThan(large.decision().reservedTokens());
    }

    @Test
    @DisplayName("Spending the token allowance refuses, even well inside the request count")
    void tokenAllowanceBinds() {
        // One request, nowhere near the 1000/min request limit, but it asks for
        // more tokens than exist. A request-counting limiter would wave it through.
        var limiter = new CostAwareLimiter();

        var first = ask(limiter, "hog", 9000, 900);
        var second = ask(limiter, "hog", 9000, 900);

        assertThat(first.allowed()).isTrue();
        assertThat(second.allowed()).isFalse();
        assertThat(second.decision().boundBy()).isEqualTo(CostAwareLimiter.Resource.TOKENS);
        assertThat(second.decision().reason()).contains("token allowance");
    }

    @Test
    @DisplayName("Settling for less than reserved gives the difference back")
    void settlementRefundsOverReservation() {
        // The estimate is deliberately generous, so without a refund a caller
        // who asked for 1000 tokens and used 50 would be charged for 1000.
        var limiter = new CostAwareLimiter();

        var first = ask(limiter, "c", 100, 4000);
        assertThat(first.allowed()).isTrue();
        first.ticket().settle(120);

        // Nearly the whole allowance is available again.
        var again = ask(limiter, "c", 100, 4000);
        assertThat(again.allowed()).isTrue();
    }

    @Test
    @DisplayName("Without settlement the reservation stands, so the limit is not a hole")
    void unsettledReservationStillCounts() {
        var limiter = new CostAwareLimiter();

        assertThat(ask(limiter, "d", 100, 4000).allowed()).isTrue();
        assertThat(ask(limiter, "d", 100, 4000).allowed()).isTrue();
        // Two 4100-token reservations against a 10000 allowance leave no room.
        assertThat(ask(limiter, "d", 100, 4000).allowed()).isFalse();
    }

    @Test
    @DisplayName("Closing an unsettled ticket returns the whole reservation")
    void closeReleasesCapacity() {
        // The request died before reaching a provider; it consumed nothing and
        // must not be charged for what it reserved.
        var limiter = new CostAwareLimiter();

        var a = ask(limiter, "e", 100, 4000);
        var b = ask(limiter, "e", 100, 4000);
        assertThat(ask(limiter, "e", 100, 4000).allowed()).isFalse();

        a.ticket().close();
        b.ticket().close();

        assertThat(ask(limiter, "e", 100, 4000).allowed()).isTrue();
    }

    @Test
    @DisplayName("Settling twice does not double-refund")
    void settlementIsIdempotent() {
        // chat() settles and then closes in a finally block, so the second call
        // always happens. It must be a no-op, or every request would hand back
        // allowance it never held.
        // Two reservations are held so the bucket is well below its cap —
        // otherwise a double refund would be clipped by the ceiling and the
        // test could not tell correct behaviour from broken.
        var limiter = new CostAwareLimiter();

        var t = ask(limiter, "f", 100, 4000).ticket();   // reserves 4100
        ask(limiter, "f", 100, 4000);                    // reserves 4100, left outstanding
        t.settle(50);                                    // refunds 4050 → 5850 left
        t.settle(50);                                    // must be a no-op
        t.close();                                       // must be a no-op

        // 5850 remains, so a 6100-token request cannot fit. Had either repeat
        // call refunded again there would be 9900 and it would sail through.
        assertThat(ask(limiter, "f", 100, 6000).allowed()).isFalse();
    }

    @Test
    @DisplayName("A caller making many tiny calls is bounded by request count, not tokens")
    void manySmallCallsAreBoundedByRequests() {
        var limiter = new CostAwareLimiter();
        // Five requests a minute, plenty of tokens.
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.reserve("g", 5, 5, 5, 1_000_000).allowed()).isTrue();
        }
        var refused = limiter.reserve("g", 5, 5, 5, 1_000_000);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.decision().boundBy()).isEqualTo(CostAwareLimiter.Resource.REQUESTS);
    }

    @Test
    @DisplayName("A refusal carries no ticket, so nothing can be settled against it")
    void refusalHasNoTicket() {
        var limiter = new CostAwareLimiter();
        limiter.reserve("h", 9000, 900, REQS, TOKENS);

        var refused = limiter.reserve("h", 9000, 900, REQS, TOKENS);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.ticket()).isNull();
    }

    @Test
    @DisplayName("Callers are accounted separately")
    void callersAreIndependent() {
        var limiter = new CostAwareLimiter();
        ask(limiter, "one", 9000, 900);

        assertThat(ask(limiter, "one", 9000, 900).allowed()).isFalse();
        assertThat(ask(limiter, "two", 9000, 900).allowed()).isTrue();
    }

    @Test
    @DisplayName("An uncapped completion reserves the assumed length, not zero")
    void uncappedCompletionIsAssumed() {
        // Reserving only the prompt would let an uncapped request through and
        // discover the cost afterwards — the failure this class exists to stop.
        var limiter = new CostAwareLimiter();

        var d = ask(limiter, "i", 100, null).decision();

        assertThat(d.reservedTokens())
                .isEqualTo(100 + CostAwareLimiter.ASSUMED_COMPLETION_TOKENS);
    }
}
