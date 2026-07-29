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
 * <p>There are now two kinds. {@link CuratedCatalogue} is a shelf of templates
 * that ship with Continuum: always available, never wrong about itself, but
 * limited to the shapes that recur. A <em>live</em> source searches a provider's
 * real directory, which covers the long tail at the cost of needing a
 * credential and a network. {@link #live()} tells the console which it is
 * looking at, because a developer should never be left to guess whether an entry
 * describes a model that exists today or a shape they will have to fill in.
 */
public interface CatalogueSource {

    /** Shown in the console so a developer knows where an entry came from. */
    String name();

    /**
     * Whether this source queries a provider's real directory.
     *
     * <p>False for the shipped templates. The distinction is worth surfacing:
     * a live result names a model that exists right now, a template describes a
     * shape the developer still has to point at something.
     */
    default boolean live() {
        return false;
    }

    /**
     * Why the source cannot answer, when {@link #available()} is false.
     *
     * <p>"Unavailable" on its own sends a developer to check their spelling.
     * "No Roboflow API key is connected" sends them to the one screen that fixes
     * it, so a source that knows the difference should say.
     */
    default String unavailableReason() {
        return null;
    }

    /**
     * An entry this source previously returned, by id, or null.
     *
     * <p>Needed because installing happens on a later request than searching.
     * The curated shelf can look one up any time; a live source can only offer
     * what it has recently seen, and returning null is the honest answer when
     * the result has aged out — the developer searches again rather than
     * installing something reconstructed from an id.
     */
    default CatalogueEntry byId(String id) {
        return null;
    }

    /**
     * Forgets anything cached for a tenant.
     *
     * <p>A live directory is cached to keep the Hub from calling a provider on
     * every keystroke, which means a developer who has just published a model
     * would not see it. This is the console's Refresh: a no-op for a source that
     * holds nothing.
     */
    default void invalidate(String developerId) {
    }

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
