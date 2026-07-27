package io.continuum.registry;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Discovery for the always-available mock provider, so the gateway and model
 * registry are fully demonstrable with zero API keys.
 *
 * <p>Two models rather than one, at different prices. A single zero-cost model
 * meant a keyless deployment could not demonstrate anything that depends on
 * there being a <em>choice</em> — the cascade declined outright, routing had one
 * arm, and hedging had nothing to race. That undercut the point of shipping a
 * mock provider at all.
 *
 * <p>{@code mock-small} is priced and behaves like a small model: it answers
 * briefly. {@code mock-large} answers at length. Neither is rigged to fail — the
 * difference is the one small models actually exhibit, which is enough for the
 * cascade's substance signal to escalate an involved question and accept a
 * simple one.
 */
@Component
public class MockModelDiscovery implements ModelDiscoveryProvider {

    /** Kept as the cheap tier's name so existing traces stay readable. */
    public static final String SMALL = "mock-small";
    public static final String LARGE = "mock-large";

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public List<DiscoveredModel> discover() {
        return List.of(
                new DiscoveredModel("mock", SMALL,
                        new ModelCapabilities(32_000, false, true, true, 0.00002, 0.00004, "small"), true),
                new DiscoveredModel("mock", LARGE,
                        new ModelCapabilities(128_000, false, true, true, 0.0005, 0.0015, "large"), true));
    }
}
