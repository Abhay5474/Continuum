package io.continuum.registry;

import java.util.List;

/**
 * Discovers the models a provider currently offers. Implementations return a
 * curated, vetted catalog (so the registry is populated even with no API keys /
 * no network) and may best-effort augment it from the provider's live model API.
 */
public interface ModelDiscoveryProvider {

    String provider();

    List<DiscoveredModel> discover();

    record DiscoveredModel(String provider, String modelName, ModelCapabilities capabilities, boolean vetted) {
    }
}
