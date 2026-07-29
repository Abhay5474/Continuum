package io.continuum.specialist;

import io.continuum.tool.Evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns specialist findings into something a language model can reason about.
 *
 * <p>This is the step the whole layer exists for. A detector's raw output —
 * bounding boxes, class indices, a JSON envelope — is not something to paste
 * into a prompt and hope. What the model needs is a short, unambiguous statement
 * of what was observed and how sure the observation was.
 *
 * <p>Two decisions shape it.
 *
 * <p><b>Confidence is stated, not implied.</b> A finding at 0.91 and one at 0.42
 * are different kinds of fact, and a model given a bare list treats them
 * identically. Writing the number, and grouping by how strong it is, is what
 * lets the model's own hedging track the evidence.
 *
 * <p><b>Absence is stated too.</b> A pipeline that found nothing must say so
 * explicitly. Sending only the user's question, with the specialist's silence
 * left as a gap, invites the model to answer as though it had seen the image
 * itself — which is precisely the failure the specialist was added to prevent.
 */
public final class ContextBuilder {

    /**
     * Above this a finding is presented as observed.
     *
     * <p>Public because {@link ConfidencePolicy} defaults to the same boundary.
     * Two private copies of 0.70 in two classes is a drift waiting to happen:
     * the prompt would say "Observed" while the policy said MEDIUM, and the
     * model's instruction would contradict the evidence right above it.
     */
    public static final double STRONG = 0.70;
    /** Between {@link #STRONG} and this it is presented as possible. */
    public static final double POSSIBLE = 0.40;

    private ContextBuilder() {
    }

    /**
     * How much recovered text one step may contribute to the prompt.
     *
     * <p>A scanned contract can run to tens of thousands of characters, and a
     * context window is finite. Clipping is stated in the prompt rather than
     * done silently — a model told it has the whole document when it has the
     * first six thousand characters will answer confidently about the part it
     * never saw.
     */
    public static final int MAX_TEXT_CHARS = 6000;

    /** How many tabular rows are shown before the rest are summarised. */
    public static final int MAX_ROWS = 25;

    /** One tool's contribution. */
    public record StepResult(String specialist, List<Evidence> evidence,
                             int dropped, String error) {

        /** Compatibility: a step that produced only classic findings. */
        public static StepResult ofFindings(String specialist,
                                            List<SpecialistProvider.Finding> findings,
                                            int dropped, String error) {
            List<Evidence> ev = new ArrayList<>();
            if (findings != null) {
                for (SpecialistProvider.Finding f : findings) {
                    ev.add(Evidence.detection(f.label(), f.confidence(), f.region()));
                }
            }
            return new StepResult(specialist, ev, dropped, error);
        }
    }

    /**
     * The assembled context: prose for the model, and the same facts as data.
     *
     * @param analysisRan whether any specialist actually answered. Distinct from
     *                    {@code anythingFound}: a clean image and a dead endpoint
     *                    both yield no findings, but only one of them is evidence,
     *                    and a confidence policy that conflates them will
     *                    confidently report "nothing wrong" about an outage.
     */
    public record Context(String prompt, Map<String, Object> structured, int totalFindings,
                          double topConfidence, boolean anythingFound, boolean analysisRan,
                          int scoredCount) {

        /**
         * Evidence exists, and none of it carries a confidence.
         *
         * <p>The case a threshold cannot speak about. Without this the OCR and
         * transcription tools land on {@code topConfidence == 0} and are graded
         * WEAK, which instructs the model to refuse an answer it has the text to
         * give.
         */
        public boolean unscoredOnly() {
            return anythingFound && scoredCount == 0;
        }
    }

    /**
     * Assembles the context.
     *
     * @param task       the pipeline's name, used to tell the model what it is doing
     * @param userPrompt whatever the application's own user asked, may be null
     */
    public static Context build(String task, String userPrompt, List<StepResult> steps) {
        List<Map<String, Object>> all = new ArrayList<>();
        List<String> strong = new ArrayList<>();
        List<String> possible = new ArrayList<>();
        List<String> weak = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int dropped = 0;
        double top = 0;

        // Unscored evidence — text, fields, rows — is collected separately,
        // because it cannot be sorted into confidence bands and pretending
        // otherwise would attach a certainty nobody measured.
        List<String> texts = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int textClipped = 0;

        for (StepResult s : steps) {
            if (s.error() != null) {
                failures.add(s.specialist());
                continue;
            }
            dropped += s.dropped();
            for (Evidence e : s.evidence()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("source", s.specialist());
                m.putAll(e.describe());
                all.add(m);

                switch (e.kind()) {
                    case DETECTION, CLASSIFICATION -> {
                        if (!e.scored()) {
                            // A label with no score: real, and not a band member.
                            notes.add(String.format("%s (unscored, from %s)",
                                    e.label(), s.specialist()));
                            break;
                        }
                        top = Math.max(top, e.confidence());
                        String line = String.format("%s (%.0f%% confident, from %s)",
                                e.label(), e.confidence() * 100, s.specialist());
                        if (e.confidence() >= STRONG) {
                            strong.add(line);
                        } else if (e.confidence() >= POSSIBLE) {
                            possible.add(line);
                        } else {
                            weak.add(line);
                        }
                    }
                    case TEXT -> {
                        String t = e.text() == null ? "" : e.text().strip();
                        if (t.isEmpty()) {
                            break;
                        }
                        if (t.length() > MAX_TEXT_CHARS) {
                            textClipped += t.length() - MAX_TEXT_CHARS;
                            t = t.substring(0, MAX_TEXT_CHARS);
                        }
                        texts.add(e.label() == null
                                ? String.format("[from %s]%n%s", s.specialist(), t)
                                : String.format("[%s, from %s]%n%s", e.label(), s.specialist(), t));
                    }
                    case FIELD -> fields.add(String.format("%s: %s  (from %s)",
                            e.label(), e.text() == null ? "" : e.text().strip(), s.specialist()));
                    case ROW -> rows.add(e.attributes());
                    case NOTE -> {
                        if (e.text() != null && !e.text().isBlank()) {
                            notes.add(e.text().strip() + " (from " + s.specialist() + ")");
                        }
                    }
                }
            }
        }

        boolean hasUnscored = !texts.isEmpty() || !fields.isEmpty() || !rows.isEmpty()
                || !notes.isEmpty();

        StringBuilder p = new StringBuilder();
        p.append("Automated analysis of the supplied ").append(task).append(" input:\n\n");

        boolean nothingRan = !steps.isEmpty() && failures.size() == steps.size();

        // --- recovered content -------------------------------------------
        // Rendered before the graded findings, because when a document has been
        // transcribed the text IS the evidence and the bands are commentary on it.
        if (!texts.isEmpty()) {
            p.append("Text recovered from the input:\n\n");
            texts.forEach(t -> p.append(t).append("\n\n"));
            if (textClipped > 0) {
                p.append("[").append(textClipped)
                        .append(" further characters were not included — the text was longer than ")
                        .append("the space available. Do not assume the part you were shown is the ")
                        .append("whole document.]\n\n");
            }
        }
        if (!fields.isEmpty()) {
            p.append("Fields extracted from the input:\n");
            fields.forEach(f -> p.append("  - ").append(f).append('\n'));
            p.append('\n');
        }
        if (!rows.isEmpty()) {
            p.append("Records extracted from the input (").append(rows.size()).append(" in total");
            if (rows.size() > MAX_ROWS) {
                p.append(", first ").append(MAX_ROWS).append(" shown");
            }
            p.append("):\n");
            rows.stream().limit(MAX_ROWS)
                    .forEach(r -> p.append("  - ").append(r).append('\n'));
            p.append('\n');
        }
        if (!notes.isEmpty()) {
            p.append("Also reported, without a confidence figure:\n");
            notes.forEach(n -> p.append("  - ").append(n).append('\n'));
            p.append('\n');
        }

        if (all.isEmpty() && !hasUnscored) {
            // Said explicitly. Silence would let the model answer as though it
            // had examined the input itself.
            if (nothingRan) {
                // "Looked and found nothing" and "never looked" are different
                // facts, and only one of them is evidence. Telling the model the
                // threshold was the reason, when in truth nothing ran, is a lie
                // in exactly the situation this layer exists to be honest about.
                p.append("No analysis is available. The specialist checks did not complete, ")
                        .append("so nothing has been examined");
            } else {
                p.append("No findings. The specialist analysis did not identify anything ")
                        .append("above the configured confidence threshold");
            }
            if (dropped > 0) {
                p.append(", though ").append(dropped)
                        .append(dropped == 1 ? " weak signal was" : " weak signals were")
                        .append(" discarded as too uncertain to report");
            }
            p.append(".\n\nYou have NOT been shown the raw input and cannot examine it yourself. ")
                    .append("Do not describe or diagnose anything the analysis did not report. ")
                    .append("Say what would be needed to give a useful answer.\n");
        } else {
            if (!strong.isEmpty()) {
                p.append("Observed:\n");
                strong.forEach(s -> p.append("  - ").append(s).append('\n'));
            }
            if (!possible.isEmpty()) {
                p.append(strong.isEmpty() ? "Possible:\n" : "\nPossible, less certain:\n");
                possible.forEach(s -> p.append("  - ").append(s).append('\n'));
            }
            if (!weak.isEmpty()) {
                p.append("\nWeak signals, treat as unconfirmed:\n");
                weak.forEach(s -> p.append("  - ").append(s).append('\n'));
            }
            if (dropped > 0) {
                p.append("\n").append(dropped)
                        .append(dropped == 1 ? " further signal was" : " further signals were")
                        .append(" below the reporting threshold and has been withheld.\n");
            }
            // Reached whenever anything at all was recovered, graded or not.
            // `graded` is false for a pure OCR or transcription step, which is
            // what stops the model being told to follow confidence figures that
            // do not exist.
            p.append('\n').append(closingInstruction(!texts.isEmpty(), !strong.isEmpty()
                    || !possible.isEmpty() || !weak.isEmpty()));
        }

        if (!failures.isEmpty()) {
            // The model should know its evidence is incomplete rather than
            // treating a partial picture as the whole one.
            p.append("\nNote: ").append(String.join(", ", failures))
                    .append(nothingRan
                            ? " did not respond, so no analysis was performed at all.\n"
                            : " did not respond, so the analysis may be incomplete.\n");
        }

        if (userPrompt != null && !userPrompt.isBlank()) {
            p.append("\nThe user asks: ").append(userPrompt.strip()).append('\n');
        }

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("task", task);
        structured.put("evidence", all);
        // Kept under its original name so consumers written against the
        // Specialist layer keep working; it is now the scored, labelled subset.
        structured.put("findings", all.stream()
                .filter(m -> m.get("confidence") != null).toList());
        structured.put("unscoredEvidence", all.size() - (int) all.stream()
                .filter(m -> m.get("confidence") != null).count());
        structured.put("belowThreshold", dropped);
        structured.put("failedSpecialists", failures);
        structured.put("userPrompt", userPrompt);

        structured.put("analysisRan", !nothingRan);

        int scored = (int) all.stream().filter(m -> m.get("confidence") != null).count();
        return new Context(p.toString(), structured, all.size(), top,
                !all.isEmpty(), !nothingRan, scored);
    }

    /**
     * What the model is told to do with the evidence.
     *
     * <p>The original wording — "you have NOT been shown the raw input" — is
     * true of a detector, which hands over labels, and <b>false</b> of an OCR or
     * transcription tool, which hands over the input's actual words. Repeating
     * it there would instruct the model to disregard the very text it was given.
     */
    private static String closingInstruction(boolean textSupplied, boolean graded) {
        StringBuilder b = new StringBuilder();
        if (textSupplied) {
            b.append("The text above was recovered from the input automatically and may contain ")
                    .append("recognition errors. Answer from it, and do not add details it does ")
                    .append("not contain. You have not seen the original file, so do not describe ")
                    .append("its appearance, layout or anything not present in the text.");
        } else {
            b.append("Base your answer on the evidence above. You have NOT been shown the raw ")
                    .append("input, so do not claim to have examined it or introduce details the ")
                    .append("analysis did not report.");
        }
        if (graded) {
            b.append(" Let your certainty follow the confidence figures above.");
        } else {
            b.append(" No confidence figures were produced for this evidence, so do not state ")
                    .append("or imply a probability of your own.");
        }
        return b.append('\n').toString();
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
