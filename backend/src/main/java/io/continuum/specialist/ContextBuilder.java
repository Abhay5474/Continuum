package io.continuum.specialist;

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

    /** Above this a finding is presented as observed. */
    private static final double STRONG = 0.70;
    /** Between {@link #STRONG} and this it is presented as possible. */
    private static final double POSSIBLE = 0.40;

    private ContextBuilder() {
    }

    /** One specialist's contribution. */
    public record StepResult(String specialist, List<SpecialistProvider.Finding> findings,
                             int dropped, String error) {
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
                          double topConfidence, boolean anythingFound, boolean analysisRan) {
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

        for (StepResult s : steps) {
            if (s.error() != null) {
                failures.add(s.specialist());
                continue;
            }
            dropped += s.dropped();
            for (SpecialistProvider.Finding f : s.findings()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("source", s.specialist());
                m.put("label", f.label());
                m.put("confidence", round(f.confidence()));
                if (f.region() != null) {
                    m.put("region", f.region());
                }
                all.add(m);
                top = Math.max(top, f.confidence());

                String line = String.format("%s (%.0f%% confident, from %s)",
                        f.label(), f.confidence() * 100, s.specialist());
                if (f.confidence() >= STRONG) {
                    strong.add(line);
                } else if (f.confidence() >= POSSIBLE) {
                    possible.add(line);
                } else {
                    weak.add(line);
                }
            }
        }

        StringBuilder p = new StringBuilder();
        p.append("Automated analysis of the supplied ").append(task).append(" input:\n\n");

        boolean nothingRan = !steps.isEmpty() && failures.size() == steps.size();

        if (all.isEmpty()) {
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
            p.append("\nBase your answer on these findings. ")
                    .append("You have NOT been shown the raw input, so do not claim to have ")
                    .append("examined it or introduce details the analysis did not report. ")
                    .append("Let your certainty follow the confidence figures above.\n");
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
        structured.put("findings", all);
        structured.put("belowThreshold", dropped);
        structured.put("failedSpecialists", failures);
        structured.put("userPrompt", userPrompt);

        structured.put("analysisRan", !nothingRan);

        return new Context(p.toString(), structured, all.size(), top, !all.isEmpty(), !nothingRan);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
