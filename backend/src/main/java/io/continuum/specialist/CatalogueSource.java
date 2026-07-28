package io.continuum.specialist;

import java.util.List;

/**
 * Somewhere the Hub can get entries from.
 *
 * <p>The interface exists because the shipped source is not the interesting one.
 * {@link CuratedCatalogue} covers the integration shapes that recur; a live
 * source that searched a provider's public model directory would cover the long
 * tail, and is the obvious next thing. Keeping the seam here means adding one
 * later is a new class rather than a rewrite of the Hub.
 *
 * <p><b>Why there is no Roboflow Universe source yet.</b> It could not be
 * verified. Every model host — {@code universe.roboflow.com},
 * {@code api.roboflow.com}, {@code huggingface.co} — is refused at CONNECT by
 * this deployment's network policy, so the search API could not be called even
 * once. Writing a client against a response shape nobody had seen would have
 * produced a feature that looked finished and 404'd on first contact with the
 * real service. The seam is here; the implementation waits for somewhere it can
 * be run against the actual API.
 */
public interface CatalogueSource {

    /** Shown in the console so a developer knows where an entry came from. */
    String name();

    /**
     * Whether this source can answer right now.
     *
     * <p>A remote source that is unreachable must say so rather than returning
     * an empty list — "no results for wound" and "the directory is down" send a
     * developer to very different places.
     */
    boolean available();

    /** Best matches first. Must not throw; an unreachable source returns nothing. */
    List<CatalogueEntry> search(String query, int limit);
}
