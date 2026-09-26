package io.continuum.registry.catalog;

/**
 * One model as the provider's own model list describes it. Nothing here is
 * scraped or guessed from a web page: every field comes from the provider API,
 * except {@code kind} and {@code note}, which are read from the model's id and
 * the generation methods it declares.
 *
 * @param contextWindow   input limit in tokens; 0 when the provider does not say
 * @param maxOutputTokens output limit in tokens; 0 when the provider does not say
 * @param createdEpoch    when the provider says the model was created (seconds); 0 when not said
 * @param preview         the provider marks it preview/experimental in its id
 * @param note            why a non-chat model is not routed, for the UI
 */
public record ListedModel(String id, ModelKind kind, String displayName, String description,
                          int contextWindow, int maxOutputTokens, long createdEpoch,
                          boolean preview, String note) {
}
