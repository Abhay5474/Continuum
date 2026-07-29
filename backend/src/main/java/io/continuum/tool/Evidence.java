package io.continuum.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One thing a tool observed, in a shape that is not assumed to be a detection.
 *
 * <p>The Specialist layer's {@code Finding(label, confidence, region)} carries a
 * detector's output perfectly and everything else not at all. Measured against
 * the real parser before this class existed: an OCR endpoint returning
 * {@code {"text": "INVOICE 4471"}} produced <b>zero</b> findings, as did a
 * transcript and a document extractor, because {@code text} is not a label and a
 * paragraph is not a class name. Three catalogue entries were therefore
 * installable, probeable, and incapable of contributing anything to a prompt.
 *
 * <p>{@code Evidence} is the general form. {@code Finding} is retained and every
 * existing detector keeps producing it — a detection simply becomes one
 * {@link Kind} of evidence rather than the only one.
 *
 * <p><b>Confidence is nullable, and that is the whole point.</b> A detector says
 * "wound, 0.87". An OCR engine says "INVOICE 4471" and has no opinion about how
 * sure it is. Storing 1.0 would assert certainty nobody claimed; storing 0.0
 * would assert the opposite and get the evidence discarded by the first
 * threshold it met. {@code null} means <em>unscored</em>, which is a third thing,
 * and the rest of the system is required to treat it as such — the same
 * distinction the layer already draws between "found nothing" and "never looked".
 */
public record Evidence(Kind kind, String label, Double confidence, String text,
                       Map<String, Object> attributes) {

    /** What kind of observation this is. */
    public enum Kind {
        /** A labelled region with a confidence. */
        DETECTION,
        /** A label for the whole input, with a confidence. */
        CLASSIFICATION,
        /** Recovered text — OCR output, a transcript, a summary. */
        TEXT,
        /** A named value pulled out of a document or payload. */
        FIELD,
        /** One record of tabular data. */
        ROW,
        /** A remark the tool made about the input, carrying no measurement. */
        NOTE
    }

    public Evidence {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** Whether this evidence carries a confidence at all. */
    public boolean scored() {
        return confidence != null;
    }

    /** The confidence, or 0 when unscored — for callers that need a number to sort by. */
    public double confidenceOrZero() {
        return confidence == null ? 0.0 : confidence;
    }

    // --- factories ----------------------------------------------------------

    public static Evidence detection(String label, double confidence, Map<String, Object> region) {
        return new Evidence(Kind.DETECTION, label, confidence, null, region);
    }

    public static Evidence classification(String label, double confidence) {
        return new Evidence(Kind.CLASSIFICATION, label, confidence, null, Map.of());
    }

    /**
     * Recovered text.
     *
     * @param label where it came from, e.g. {@code "page 2"} — optional
     */
    public static Evidence text(String label, String text, Map<String, Object> attributes) {
        return new Evidence(Kind.TEXT, label, null, text, attributes);
    }

    /**
     * A named value.
     *
     * @param confidence some extractors score their fields; most do not. Null is
     *                   the normal case and must not be turned into a number.
     */
    public static Evidence field(String name, String value, Double confidence) {
        return new Evidence(Kind.FIELD, name, confidence, value, Map.of());
    }

    public static Evidence row(Map<String, Object> row) {
        return new Evidence(Kind.ROW, null, null, null, row);
    }

    public static Evidence note(String text) {
        return new Evidence(Kind.NOTE, null, null, text, Map.of());
    }

    // --- projection ---------------------------------------------------------

    /**
     * Whether this can be represented as a legacy {@code Finding}.
     *
     * <p>Only scored, labelled evidence can. Everything else would have to
     * invent either a label or a confidence to fit, and inventing a confidence
     * is precisely what this class exists to stop.
     */
    public boolean isFindingShaped() {
        return (kind == Kind.DETECTION || kind == Kind.CLASSIFICATION) && label != null && scored();
    }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind.name());
        if (label != null) {
            m.put("label", label);
        }
        // Present as null rather than absent: a consumer must be able to tell
        // "unscored" from "the field was omitted by an older version".
        m.put("confidence", confidence == null ? null
                : Math.round(confidence * 1000) / 1000.0);
        m.put("scored", scored());
        if (text != null) {
            m.put("text", text);
        }
        if (!attributes.isEmpty()) {
            m.put("attributes", attributes);
        }
        return m;
    }

    /**
     * Forces scored evidence onto the 0–1 scale and drops what cannot be trusted.
     *
     * <p>The same rule the Specialist layer already applied to findings, extended
     * to the general case: a value in 0–1 is taken as given, 1–100 is read as a
     * percentage, and anything outside that — or NaN, or negative — is
     * <b>dropped rather than clamped</b>, because a clamped 12500 becomes a
     * confident 1.0 and defeats every threshold downstream.
     *
     * <p>Unscored evidence passes through untouched. There is nothing to
     * normalise, and discarding it would delete the output of every OCR,
     * transcription and extraction tool in the system.
     */
    public static List<Evidence> normalise(List<Evidence> raw) {
        if (raw == null) {
            return List.of();
        }
        List<Evidence> out = new ArrayList<>(raw.size());
        for (Evidence e : raw) {
            if (e == null) {
                continue;
            }
            if (!e.scored()) {
                out.add(e);
                continue;
            }
            double c = e.confidence();
            if (Double.isNaN(c) || Double.isInfinite(c) || c < 0 || c > 100) {
                continue;
            }
            out.add(c <= 1 ? e
                    : new Evidence(e.kind(), e.label(), c / 100.0, e.text(), e.attributes()));
        }
        // Strongest first among scored; unscored keeps its original order after
        // them, since there is no meaningful way to rank text against text.
        out.sort((a, b) -> {
            if (a.scored() != b.scored()) {
                return a.scored() ? -1 : 1;
            }
            return Double.compare(b.confidenceOrZero(), a.confidenceOrZero());
        });
        return out;
    }

    /** Text kinds, for callers deciding how to present a step. */
    public static boolean carriesText(List<Evidence> evidence) {
        return evidence != null && evidence.stream()
                .anyMatch(e -> e.kind() == Kind.TEXT && e.text() != null && !e.text().isBlank());
    }

    /** A short human label for a single piece of evidence, for traces and logs. */
    public String summarise(int maxTextChars) {
        return switch (kind) {
            case DETECTION, CLASSIFICATION -> String.format(Locale.ROOT, "%s (%.0f%%)",
                    label, confidenceOrZero() * 100);
            case FIELD -> label + ": " + clip(text, maxTextChars);
            case TEXT -> clip(text, maxTextChars);
            case ROW -> attributes.toString().length() > maxTextChars
                    ? attributes.toString().substring(0, maxTextChars) + "…"
                    : attributes.toString();
            case NOTE -> clip(text, maxTextChars);
        };
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
