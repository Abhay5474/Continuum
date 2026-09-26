package io.continuum.registry.catalog;

import java.util.List;

/**
 * The outcome of asking a provider for its models.
 *
 * <p>A failed list is never read as "the provider has no models": nothing is
 * retired on the strength of a network error or a refused key.
 */
public record ListResult(boolean ok, List<ListedModel> models, String error, boolean keyRejected) {

    public static ListResult ok(List<ListedModel> models) {
        return new ListResult(true, List.copyOf(models), null, false);
    }

    public static ListResult failed(String error) {
        return new ListResult(false, List.of(), error, false);
    }

    public static ListResult keyRejected(String error) {
        return new ListResult(false, List.of(), error, true);
    }
}
