package io.continuum.registry.catalog;

import java.util.List;

/**
 * Reads one provider's model catalogue from the provider itself.
 *
 * <p>Two calls, both cheap: the model list (one GET, no tokens) and a probe (one
 * chat request capped at a handful of output tokens). The catalogue service
 * decides how often either happens; implementations only speak the wire format.
 */
public interface ProviderCatalogClient {

    String provider();

    /** A key is configured, so the provider can be asked at all. */
    boolean configured();

    /** The key is declared free-tier, so "answered a test call" means "free". */
    boolean freeTier();

    ListResult list();

    ProbeResult probe(String modelId);

    /** Known-good model ids, used only until the first successful list. Never re-asserted after it. */
    List<ListedModel> seeds();

    /**
     * Names the provider has announced it retired, with the announcement, for
     * catalogues that have not read a list yet. Only ever retires; the first
     * successful list is the authority either way.
     */
    default java.util.Map<String, String> retiredBeforeCatalogue() {
        return java.util.Map.of();
    }

    /**
     * Whether a failed chat call says the model itself is gone (retired or
     * renamed), as opposed to busy, broken or refused for another reason.
     */
    boolean isModelGone(int status, String body);
}
