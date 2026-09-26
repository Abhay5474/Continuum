package io.continuum.registry.catalog;

import java.util.List;
import java.util.Map;

/**
 * What each provider's own terms say, in brief, with the page it comes from.
 *
 * <p>Deliberately not fetched at runtime: scraping terms pages is brittle and a
 * page can say anything. These are written from the providers' published
 * documents, dated, and each links to the source — the link is the authority,
 * and the UI says so. Per-model facts (whether a model is free, its limits,
 * whether it still exists) are not here: they come from the providers' APIs.
 */
public final class ProviderPolicies {

    /** When these notes were last checked against the linked pages. */
    public static final String REVIEWED = "2026-09-26";

    public record Note(String title, String body) {
    }

    public record Link(String label, String url) {
    }

    public record Policy(String provider, String reviewed, List<Note> notes, List<Link> links) {
    }

    private ProviderPolicies() {
    }

    public static Map<String, Policy> all() {
        return Map.of(
                "groq", new Policy("groq", REVIEWED, List.of(
                        new Note("Free tier",
                                "Every model on Groq's free plan has its own request and token limits. The limits "
                                        + "shown for a model are the ones Groq reported for this key on its last test call."),
                        new Note("Retirements",
                                "Groq retires models on a published schedule, and can retire them for free and "
                                        + "developer accounts before enterprise ones. On 16 August 2026 it retired Llama 3.3 "
                                        + "70B Versatile and Llama 3.1 8B Instant for free and developer accounts; requests "
                                        + "naming them now get 404 model_not_found."),
                        new Note("Not every listed model is for chat",
                                "Groq's list includes speech-to-text, text-to-speech and safety models. They are "
                                        + "shown here but never sent chat requests.")),
                        List.of(new Link("Supported models", "https://console.groq.com/docs/models"),
                                new Link("Deprecations", "https://console.groq.com/docs/deprecations"),
                                new Link("Rate limits", "https://console.groq.com/docs/rate-limits"))),
                "gemini", new Policy("gemini", REVIEWED, List.of(
                        new Note("Your data on the free tier",
                                "On the free tier (the unpaid quota of the Gemini API), Google uses what you send and "
                                        + "what the model returns to improve its products, and human reviewers may read "
                                        + "it after it is disconnected from your account and key. Do not send sensitive, "
                                        + "confidential or personal information on the free tier. On a paid tier it is "
                                        + "not used this way."),
                        new Note("Free tier",
                                "Not every Gemini model has a free tier, and free limits differ per model. A model is "
                                        + "shown as free only after it answered a test call on this key; one without free "
                                        + "quota answers with a limit of zero and is marked not free."),
                        new Note("Retirements",
                                "Google announces shutdown dates on its deprecations page. Preview and experimental "
                                        + "models can change or be withdrawn at shorter notice, so they are never chosen "
                                        + "as a default while a stable model is usable.")),
                        List.of(new Link("Models", "https://ai.google.dev/gemini-api/docs/models"),
                                new Link("Deprecations", "https://ai.google.dev/gemini-api/docs/deprecations"),
                                new Link("Rate limits", "https://ai.google.dev/gemini-api/docs/rate-limits"),
                                new Link("Pricing", "https://ai.google.dev/gemini-api/docs/pricing"),
                                new Link("Additional terms", "https://ai.google.dev/gemini-api/terms"))));
    }
}
