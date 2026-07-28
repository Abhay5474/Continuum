package io.continuum.specialist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the answer against the evidence it was supposed to be based on.
 *
 * <p>The confidence policy asks the model to behave and {@link HedgeDetector}
 * checks whether it did — but both are about the *instruction*. Neither looks at
 * whether the advice is actually anchored to what the specialists found. An
 * answer can hedge beautifully and still describe an injury nobody detected.
 *
 * <p>Three checks, chosen because each has a definite answer:
 *
 * <ol>
 *   <li><b>Certainty beyond the evidence.</b> Flat assertions — "this is a",
 *       "clearly", "confirmed" — when the strongest finding was weak, or when
 *       there was nothing at all. This is the failure that matters most and it
 *       is the one a word list can genuinely catch.</li>
 *   <li><b>Invented figures.</b> A percentage in the answer matching no
 *       finding's confidence and appearing nowhere in the question. Models
 *       restate confidences, and they also make them up; the difference is
 *       checkable.</li>
 *   <li><b>Coverage.</b> Which findings the advice actually addresses. A
 *       detector reporting bleeding and advice that never mentions it is
 *       incomplete relative to its own evidence.</li>
 * </ol>
 *
 * <p><b>Coverage can only ever warn.</b> The match is lexical, so a model that
 * writes "laceration" for a finding labelled "open wound" reads as uncovered
 * when it covered the finding perfectly. Failing an answer on that would punish
 * good writing, so an uncovered finding is reported and never fatal. The two
 * checks that can fail an answer are the two that do not depend on the model
 * having chosen the same words.
 *
 * <p><b>What this cannot do.</b> It cannot tell whether the advice is correct.
 * It checks whether the advice is anchored to the findings, which is a different
 * and smaller question — but it is the one that catches a model inventing a
 * diagnosis, and that is worth catching.
 */
public final class AnswerVerifier {

    /** What the pipeline does with a failing answer. */
    public enum Mode {
        /** No checking. The default. */
        OFF,
        /** Check and record; the answer goes out unchanged. */
        MONITOR,
        /** Check, and replace an answer that fails. */
        ENFORCE
    }

    public enum Verdict { OK, WARN, FAIL }

    private AnswerVerifier() {
    }

    /** Marks of unqualified assertion. */
    private static final String[] CERTAINTY = {
            "this is a", "this is an", "the animal has", "it is a", "clearly", "definitely",
            "certainly", "without doubt", "obviously", "diagnosis is", "confirmed", "is definitely",
            "there is a", "i can see", "i can confirm",
    };

    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3})\\s*(?:%|percent)");

    /** Words too common to prove a finding was addressed. */
    private static final Set<String> STOP = Set.of(
            "the", "a", "an", "of", "and", "or", "in", "on", "at", "to", "is", "it", "with");

    /**
     * @param kind   short machine-readable label
     * @param detail written for the developer reading it
     * @param severe whether this alone fails the answer
     */
    public record Issue(String kind, String detail, boolean severe) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("detail", detail);
            m.put("severe", severe);
            return m;
        }
    }

    public record Result(Verdict verdict, List<Issue> issues, List<String> covered,
                         List<String> uncovered, boolean replaced, String method) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("verdict", verdict.name());
            m.put("issues", issues.stream().map(Issue::describe).toList());
            m.put("covered", covered);
            m.put("uncovered", uncovered);
            m.put("replaced", replaced);
            m.put("method", method);
            return m;
        }
    }

    /**
     * @param weakAt below this, the evidence is too weak to assert on
     */
    public static Result check(String answer, ContextBuilder.Context ctx, String userPrompt,
                               double weakAt) {
        String text = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        String question = userPrompt == null ? "" : userPrompt.toLowerCase(Locale.ROOT);
        List<Issue> issues = new ArrayList<>();

        // --- 1. certainty beyond the evidence --------------------------------
        List<String> asserted = new ArrayList<>();
        for (String c : CERTAINTY) {
            // Word boundaries, not substrings: "confirmed" must not match
            // "unconfirmed", which is the phrase the context builder uses for
            // the weakest findings there are.
            if (Phrases.contains(text, c)) {
                asserted.add(c);
            }
        }
        if (!asserted.isEmpty()) {
            if (!ctx.analysisRan()) {
                issues.add(new Issue("certainty-without-analysis",
                        "The answer asserts (" + String.join(", ", asserted)
                                + ") but no specialist answered, so nothing was examined.", true));
            } else if (!ctx.anythingFound()) {
                issues.add(new Issue("certainty-without-findings",
                        "The answer asserts (" + String.join(", ", asserted)
                                + ") but the analysis found nothing.", true));
            } else if (ctx.topConfidence() < weakAt) {
                issues.add(new Issue("certainty-beyond-evidence",
                        "The answer asserts (" + String.join(", ", asserted)
                                + ") but the strongest finding was only "
                                + Math.round(ctx.topConfidence() * 100) + "%.", true));
            }
        }

        // --- 2. invented figures ---------------------------------------------
        Set<Integer> allowed = new LinkedHashSet<>();
        for (Map<String, Object> f : findings(ctx)) {
            Object c = f.get("confidence");
            if (c instanceof Number n) {
                // A model may round either way when restating a confidence.
                allowed.add((int) Math.round(n.doubleValue() * 100));
                allowed.add((int) Math.floor(n.doubleValue() * 100));
                allowed.add((int) Math.ceil(n.doubleValue() * 100));
            }
        }
        List<String> invented = new ArrayList<>();
        Matcher m = PERCENT.matcher(text);
        while (m.find()) {
            int v = Integer.parseInt(m.group(1));
            // Anything the user themselves said is theirs, not an invention.
            if (!allowed.contains(v) && !question.contains(String.valueOf(v))) {
                invented.add(v + "%");
            }
        }
        if (!invented.isEmpty()) {
            issues.add(new Issue("invented-figure",
                    "The answer states " + String.join(", ", invented)
                            + ", which matches no finding's confidence and was not in the question.",
                    true));
        }

        // --- 3. coverage ------------------------------------------------------
        List<String> covered = new ArrayList<>();
        List<String> uncovered = new ArrayList<>();
        for (Map<String, Object> f : findings(ctx)) {
            String label = String.valueOf(f.get("label"));
            if (mentions(text, label)) {
                covered.add(label);
            } else {
                uncovered.add(label);
            }
        }
        if (!uncovered.isEmpty()) {
            // Warning only. The match is lexical, so a model writing
            // "laceration" for "open wound" reads as uncovered while having
            // covered it perfectly, and failing an answer for that would
            // punish good writing.
            issues.add(new Issue("uncovered-finding",
                    "The advice does not mention " + String.join(", ", uncovered)
                            + ". This may just be wording — the check is lexical.", false));
        }

        Verdict verdict = issues.stream().anyMatch(Issue::severe) ? Verdict.FAIL
                : issues.isEmpty() ? Verdict.OK : Verdict.WARN;

        return new Result(verdict, issues, covered, uncovered, false, "lexical");
    }

    /** The message that replaces a failing answer under {@code ENFORCE}. */
    public static String replacement(ContextBuilder.Context ctx) {
        if (!ctx.analysisRan()) {
            return "This could not be assessed — the automated analysis did not complete, so "
                    + "nothing was examined. Please try again shortly.";
        }
        if (!ctx.anythingFound()) {
            return "Nothing could be identified from what was provided with enough confidence to "
                    + "advise on. A clearer or closer input would help.";
        }
        StringBuilder b = new StringBuilder(
                "The automated analysis found the following, but a reliable answer could not be "
                        + "produced from it:\n");
        for (Map<String, Object> f : findings(ctx)) {
            b.append("  - ").append(f.get("label")).append(" (")
                    .append(Math.round(((Number) f.get("confidence")).doubleValue() * 100))
                    .append("% confident)\n");
        }
        b.append("Please try again, or seek advice from someone who can examine this directly.");
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> findings(ContextBuilder.Context ctx) {
        Object f = ctx.structured().get("findings");
        return f instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /**
     * Whether the answer addresses a finding.
     *
     * <p>Matches the whole label, or the label's head word — "open wound" is
     * covered by an answer that says "wound". Common words alone never count, or
     * every finding would look covered by any prose.
     */
    private static boolean mentions(String text, String label) {
        String l = label.toLowerCase(Locale.ROOT).strip();
        if (l.isEmpty()) {
            return true;
        }
        if (text.contains(l)) {
            return true;
        }
        String[] words = l.split("\\s+");
        String head = words[words.length - 1];
        return head.length() > 3 && !STOP.contains(head)
                && Pattern.compile("\\b" + Pattern.quote(head)).matcher(text).find();
    }
}
