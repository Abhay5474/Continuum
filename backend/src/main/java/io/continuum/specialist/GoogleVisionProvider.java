package io.continuum.specialist;

import io.continuum.persistence.entity.SpecialistConnectionEntity;
import io.continuum.tool.Evidence;
import io.continuum.tool.MediaBytes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Google Cloud Vision — OCR, labelling and safe-search, on one endpoint.
 *
 * <p>Lower priority than OCR.space deliberately: Google is more accurate and
 * more work to start using. It is here because the accuracy matters for real
 * documents, and because the setup is smaller than its reputation — Vision
 * accepts a plain <b>API key as a query parameter</b>, so a developer does not
 * need a service account, a signed JWT or the gcloud CLI to try it. The free
 * tier covers the first 1,000 units a month.
 *
 * <p>One endpoint, {@code images:annotate}, does everything; which job it does is
 * chosen by the {@code features} in the request. Continuum maps the model path
 * onto that, so the same adapter serves three quite different tools:
 *
 * <ul>
 *   <li>{@code TEXT_DETECTION} / {@code DOCUMENT_TEXT_DETECTION} — OCR, returning
 *       unscored text;</li>
 *   <li>{@code LABEL_DETECTION} — what is in the picture, returning scored
 *       classifications;</li>
 *   <li>{@code SAFE_SEARCH_DETECTION} — moderation, returning categories.</li>
 * </ul>
 *
 * <p><b>The interesting mapping is safe-search.</b> Google answers with words —
 * {@code VERY_UNLIKELY} through {@code VERY_LIKELY} — not numbers. Those are
 * ordered categories, and turning them into confidences would be inventing
 * precision Google did not offer: there is no defensible number for "POSSIBLE".
 * They are returned as unscored fields carrying the word, so a moderation
 * decision is made on the category Google actually gave rather than on a
 * threshold applied to a fabricated score.
 *
 * <p><b>LIVE_UNVERIFIED.</b> Written against Google's documented annotate API and
 * never run against the real service — this deployment reaches no external
 * provider. The mapping is covered by fixture tests; the network path is not.
 */
public class GoogleVisionProvider implements SpecialistProvider {

    /** Chosen when the developer names no feature: the commonest use. */
    static final String DEFAULT_FEATURE = "DOCUMENT_TEXT_DETECTION";

    /** Safe-search verdicts, weakest first, as Google orders them. */
    static final List<String> LIKELIHOOD = List.of(
            "UNKNOWN", "VERY_UNLIKELY", "UNLIKELY", "POSSIBLE", "LIKELY", "VERY_LIKELY");

    @Override
    public String name() {
        return "googlevision";
    }

    @Override
    public String label() {
        return "Google Cloud Vision (OCR, labels, safe search)";
    }

    @Override
    public String defaultBaseUrl() {
        return "https://vision.googleapis.com";
    }

    @Override
    public SpecialistConnectionEntity.AuthStyle defaultAuthStyle() {
        // An API key on the query string. Vision accepts one, which is the whole
        // reason this adapter is approachable at all.
        return SpecialistConnectionEntity.AuthStyle.QUERY;
    }

    @Override
    public String defaultAuthParam() {
        return "key";
    }

    @Override
    public List<String> inputKinds() {
        return List.of("image", "document");
    }

    @Override
    public Call buildCall(SpecialistConnectionEntity connection, String modelPath,
                          Map<String, Object> input) {
        String base = connection.getBaseUrl() == null || connection.getBaseUrl().isBlank()
                ? defaultBaseUrl()
                : connection.getBaseUrl().replaceAll("/+$", "");

        String feature = modelPath == null || modelPath.isBlank()
                ? DEFAULT_FEATURE
                : modelPath.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace('/', '_');

        String encoded = MediaBytes.findEncoded(input, MediaBytes.IMAGE_KEYS);
        if (encoded == null) {
            encoded = MediaBytes.findEncoded(input, MediaBytes.DOCUMENT_KEYS);
        }

        Map<String, Object> image = new LinkedHashMap<>();
        if (encoded != null) {
            image.put("content", encoded);
        } else {
            Object url = input.get("imageUrl");
            if (url instanceof String s && MediaBytes.isUrl(s)) {
                // Google fetches it itself, which avoids pushing the file
                // through Continuum twice.
                image.put("source", Map.of("imageUri", s.strip()));
            } else {
                throw new IllegalArgumentException(
                        "No image was supplied. Send it as \"imageBase64\", or give \"imageUrl\" "
                                + "for a file Google can fetch itself.");
            }
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("image", image);
        request.put("features", List.of(Map.of("type", feature, "maxResults", 20)));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        return new Call(base + "/v1/images:annotate", "POST", headers,
                Map.of("requests", List.of(request)));
    }

    @Override
    public List<Finding> parse(Object responseBody) {
        List<Finding> out = new ArrayList<>();
        for (Evidence e : parseEvidence(responseBody)) {
            if (e.isFindingShaped()) {
                out.add(new Finding(e.label(), e.confidence(), null));
            }
        }
        return out;
    }

    @Override
    public List<Evidence> parseEvidence(Object responseBody) {
        if (!(responseBody instanceof Map<?, ?> body)) {
            return List.of();
        }
        if (!(body.get("responses") instanceof List<?> responses) || responses.isEmpty()) {
            // A malformed key or a disabled API answers with a top-level error.
            if (body.get("error") instanceof Map<?, ?> err) {
                return List.of(Evidence.note("Google Vision returned an error: "
                        + String.valueOf(err.get("message")).strip()));
            }
            return List.of();
        }
        if (!(responses.get(0) instanceof Map<?, ?> first)) {
            return List.of();
        }

        // Per-request errors sit inside the response, not in the HTTP status.
        if (first.get("error") instanceof Map<?, ?> err && err.get("message") != null) {
            return List.of(Evidence.note("Google Vision could not process this image: "
                    + String.valueOf(err.get("message")).strip()));
        }

        List<Evidence> out = new ArrayList<>();

        // OCR. fullTextAnnotation is the whole page with layout preserved, which
        // is more useful to a language model than the word-by-word list.
        if (first.get("fullTextAnnotation") instanceof Map<?, ?> full
                && full.get("text") instanceof String text && !text.isBlank()) {
            out.add(Evidence.text("Recognised text", text.strip(), Map.of()));
        } else if (first.get("textAnnotations") instanceof List<?> anns && !anns.isEmpty()
                && anns.get(0) instanceof Map<?, ?> whole
                && whole.get("description") instanceof String d && !d.isBlank()) {
            // Plain TEXT_DETECTION: the first annotation is the whole block, and
            // the rest are the individual words already inside it.
            out.add(Evidence.text("Recognised text", d.strip(), Map.of()));
        }

        // Labels: real class probabilities, so genuinely scored.
        if (first.get("labelAnnotations") instanceof List<?> labels) {
            for (Object o : labels) {
                if (o instanceof Map<?, ?> l && l.get("description") != null
                        && l.get("score") instanceof Number n) {
                    out.add(Evidence.classification(l.get("description").toString(), n.doubleValue()));
                }
            }
        }

        // Safe search: ordered words, not numbers. Kept as words.
        if (first.get("safeSearchAnnotation") instanceof Map<?, ?> safe) {
            safe.forEach((k, v) -> {
                if (k == null || v == null) {
                    return;
                }
                String verdict = v.toString().toUpperCase(Locale.ROOT);
                if (!LIKELIHOOD.contains(verdict)) {
                    return;
                }
                // A field rather than a classification: Google said "POSSIBLE",
                // and there is no honest number for that. A pipeline decides on
                // the word, which is the thing Google actually asserted.
                out.add(Evidence.field(k.toString(), verdict, null));
            });
        }

        if (out.isEmpty()) {
            // Vision answers {} for an image it found nothing in. Said plainly,
            // because a blank answer and a failed call look identical otherwise.
            return List.of(Evidence.note(
                    "Google Vision processed the image and found nothing for the requested "
                            + "feature. For OCR that usually means the image has no legible text."));
        }
        return out;
    }
}
