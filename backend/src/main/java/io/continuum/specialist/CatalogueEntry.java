package io.continuum.specialist;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One thing you can add from the Hub.
 *
 * <p>An entry is a <b>template</b>, not a claim that a particular third-party
 * model exists. It carries the answers to the questions that actually stop
 * people integrating: which provider, how the credential is presented, what
 * response shape to expect, what confidence threshold is sensible for this kind
 * of task. What it deliberately does not carry is an invented model id — a
 * catalogue whose entries 404 on first probe is worse than no catalogue.
 *
 * <p>{@link #needs} is what the developer must still supply. Being explicit
 * about it up front is the difference between a form that fills itself in and a
 * form that fails validation after you press the button.
 *
 * @param id                  stable identifier, used by the install endpoint
 * @param title               what a person would call this
 * @param description         what it does, in one sentence
 * @param provider            {@code roboflow}, {@code http}, …
 * @param baseUrl             pre-filled when the provider has a fixed home
 * @param modelPath           pre-filled when the shape is fixed; empty when yours
 * @param inputKind           image | text | json | audio
 * @param suggestedConfidence a starting threshold appropriate to the task
 * @param tags                what it is searched by
 * @param needs               fields the developer must provide
 * @param note                the honest caveat, shown next to the button
 * @param source              which catalogue produced this
 */
public record CatalogueEntry(String id, String title, String description, String provider,
                             String baseUrl, String modelPath, String inputKind,
                             double suggestedConfidence, List<String> tags, List<String> needs,
                             String note, String source) {

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("description", description);
        m.put("provider", provider);
        m.put("baseUrl", baseUrl);
        m.put("modelPath", modelPath);
        m.put("inputKind", inputKind);
        m.put("suggestedConfidence", suggestedConfidence);
        m.put("tags", tags);
        m.put("needs", needs);
        m.put("note", note);
        m.put("source", source);
        return m;
    }

    /** How well this entry answers a query. Zero means it should not be shown. */
    public int score(String query) {
        if (query == null || query.isBlank()) {
            return 1;
        }
        String q = query.toLowerCase(java.util.Locale.ROOT).strip();
        int score = 0;
        // Title matches rank hardest: someone typing "ocr" wants the OCR entry
        // first, not everything whose description happens to mention text.
        if (title.toLowerCase(java.util.Locale.ROOT).contains(q)) {
            score += 10;
        }
        for (String tag : tags) {
            if (tag.equalsIgnoreCase(q)) {
                score += 6;
            } else if (tag.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                score += 3;
            }
        }
        if (description.toLowerCase(java.util.Locale.ROOT).contains(q)) {
            score += 2;
        }
        if (provider.equalsIgnoreCase(q)) {
            score += 4;
        }
        return score;
    }
}
