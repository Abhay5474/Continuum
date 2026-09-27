package io.continuum.chaos;

import io.continuum.testsupport.FakeLlmProvider;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The injected provider failure.
 *
 * <p>The reliability benchmark's whole value is that two runs of the same
 * command produce the same numbers. That rests on this being deterministic
 * rather than a random draw, so it is asserted here: a benchmark whose failure
 * count wanders by a few percent between runs cannot be used to argue that
 * anything improved, and nobody would notice it had started wandering.
 */
class ProviderFailureInjectionTest {

    @Test
    void failsExactlyTheRequestedFractionEvenlySpaced() {
        ChaosMonkey chaos = new ChaosMonkey();
        chaos.setProviderFailureRate(null, 0.25);

        int failures = 0;
        for (int i = 0; i < 100; i++) {
            if (chaos.shouldFailProviderCall()) {
                failures++;
            }
        }
        // Exactly floor(100 * 0.25), not "about 25".
        assertThat(failures).isEqualTo(25);
    }

    @Test
    void isReproducibleAcrossRuns() {
        assertThat(pattern(0.3, 20)).isEqualTo(pattern(0.3, 20));
    }

    @Test
    void spacesFailuresRatherThanClusteringThem() {
        // Clustered failures would exhaust a failover chain and produce visible
        // errors, which would make the benchmark measure the clustering rather
        // than the product.
        String p = pattern(0.25, 20);
        assertThat(p).doesNotContain("XX");
    }

    @Test
    void isOffUntilArmed() {
        ChaosMonkey chaos = new ChaosMonkey();
        for (int i = 0; i < 50; i++) {
            assertThat(chaos.shouldFailProviderCall()).isFalse();
        }
    }

    @Test
    void resetDisarmsItAndRewindsTheSchedule() {
        ChaosMonkey chaos = new ChaosMonkey();
        chaos.setProviderFailureRate(null, 1.0);
        assertThat(chaos.shouldFailProviderCall()).isTrue();

        chaos.reset(null);
        assertThat(chaos.shouldFailProviderCall()).isFalse();
        assertThat(chaos.state(null).providerFailureRate()).isZero();
    }

    /** The router applies it to every provider, before calling it. */
    private static io.continuum.provider.ProviderRouter routerWith(ChaosMonkey chaos) {
        io.continuum.config.LlmProperties props = new io.continuum.config.LlmProperties();
        props.setFailoverOrder(List.of("fake"));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<io.continuum.routing.ProviderMetrics> metrics =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        var router = new io.continuum.provider.ProviderRouter(List.of(new FakeLlmProvider()), props, metrics);
        org.springframework.test.util.ReflectionTestUtils.setField(router, "chaos", chaos);
        return router;
    }

    @Test
    void theRouterFailsAnyProviderWhenArmed() {
        ChaosMonkey chaos = new ChaosMonkey();
        chaos.setProviderFailureRate(null, 1.0);

        assertThatThrownBy(() -> routerWith(chaos).complete(
                new LlmRequest("m", List.of(Message.user("hi")), 50, 0.2), List.of("fake"), null))
                .hasRootCauseInstanceOf(ChaosMonkey.SimulatedProviderFailure.class);
    }

    @Test
    void theRouterIsUnaffectedWhenNothingIsArmed() {
        var resp = routerWith(new ChaosMonkey()).complete(
                new LlmRequest("m", List.of(Message.user("hi")), 50, 0.2), List.of("fake"), null);
        assertThat(resp.content()).isNotBlank();
    }

    /** "X" for a failure, "." for a success, so a pattern is readable in a diff. */
    private String pattern(double rate, int calls) {
        ChaosMonkey chaos = new ChaosMonkey();
        chaos.setProviderFailureRate(null, rate);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < calls; i++) {
            out.append(chaos.shouldFailProviderCall() ? 'X' : '.');
        }
        return out.toString();
    }
}
