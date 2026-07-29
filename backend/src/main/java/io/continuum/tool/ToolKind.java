package io.continuum.tool;

import java.util.Locale;

/**
 * What shape of work a tool does.
 *
 * <p>The Specialist layer was built around one shape — a detector returning
 * labelled boxes — and its data model quietly assumed it everywhere. This enum
 * is the first place that assumption is written down rather than implied, so a
 * tool that returns text can be told apart from one that returns detections
 * before either of them runs.
 *
 * <p>Kind is <b>not</b> the same as input kind. An OCR tool and a document
 * extractor both take a document; they return completely different things, and
 * it is the return shape that decides how the evidence reaches the model.
 */
public enum ToolKind {

    /** Labelled regions with confidences. The original Specialist shape. */
    DETECTION(true),
    /** Labels for the whole input, with confidences. */
    CLASSIFICATION(true),
    /** Safety scoring. Classification by another name, kept separate because the
     *  thresholds point the other way — a moderation check that misses is useless. */
    MODERATION(true),

    /** Text recovered from an image or document. Carries no confidence. */
    OCR(false),
    /** Speech turned into text. Carries no confidence. */
    TRANSCRIPTION(false),
    /** Named fields pulled out of a document. */
    EXTRACTION(false),
    /** One data shape converted into another. */
    TRANSFORMATION(false),
    /** A check that answers pass or fail with reasons. */
    VALIDATION(false),
    /** Anything else the developer runs. */
    CUSTOM(false);

    private final boolean scored;

    ToolKind(boolean scored) {
        this.scored = scored;
    }

    /**
     * Whether this kind of tool produces confidences at all.
     *
     * <p>The distinction that makes the rest of the system honest. A confidence
     * threshold is a statement about scores; applying one to OCR text would
     * silently discard everything, because there is no score to compare.
     */
    public boolean isScored() {
        return scored;
    }

    /** Unrecognised values read as CUSTOM — the kind that assumes least. */
    public static ToolKind of(String raw) {
        if (raw == null || raw.isBlank()) {
            return CUSTOM;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CUSTOM;
        }
    }

    public String label() {
        return switch (this) {
            case DETECTION -> "Detection";
            case CLASSIFICATION -> "Classification";
            case MODERATION -> "Moderation";
            case OCR -> "Text extraction (OCR)";
            case TRANSCRIPTION -> "Audio transcription";
            case EXTRACTION -> "Document field extraction";
            case TRANSFORMATION -> "Data transformation";
            case VALIDATION -> "Validation";
            case CUSTOM -> "Custom";
        };
    }
}
