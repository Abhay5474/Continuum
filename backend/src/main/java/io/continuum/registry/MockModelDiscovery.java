package io.continuum.registry;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Discovery for the always-available mock provider, so the gateway and model
 * registry are fully demonstrable with zero API keys.
 */
@Component
public class MockModelDiscovery implements ModelDiscoveryProvider {

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public List<DiscoveredModel> discover() {
        return List.of(new DiscoveredModel("mock", "mock-1",
                new ModelCapabilities(32_000, false, true, true, 0.0, 0.0, "mock"), true));
    }
}
