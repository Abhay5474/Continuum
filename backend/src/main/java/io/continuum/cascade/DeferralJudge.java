package io.continuum.cascade;

import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import io.continuum.semantic.TextVectors;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides whether a cheap model's answer is good enough to return.
 *
 * <p>This is the whole cascade in one class. Escalating on <em>errors</em> is
 * what the failover chain already did; a cheap model producing a confidently
 * wrong answer is a success as far as that chain is concerned. Cascading needs a
 * judgement about the answer itself.
 *
 * <p>The judge is deliberately <b>deterministic and free</b>. It could ask a
 * model to score the answer, and that is the obvious design, but it would add a
 * call and a latency budget to the path whose entire purpose is being cheap and
 * fast. Instead it reads several signals that are objectively checkable, and
 * each one is named in the output so an escalation can be explained rather than
 * asserted:
 *
 * <ul>
 *   <li><b>Hedging language.</b> "I don't know", "I cannot determine", "as an AI"
 *       — a model telling you it failed is the cheapest signal available.</li>
 *   <li><b>Truncation.</b> An answer that stops mid-sentence is incomplete
 *       regardless of how good the part before it was.</li>
 *   <li><b>Format compliance.</b> If the prompt asked for JSON and the answer
 *       does not parse, it is wrong in a way no amount of eloquence fixes.</li>
 *   <li><b>Explicit constraints.</b> "In exactly three bullets" is checkable.</li>
 *   <li><b>Grounding.</b> Lexical overlap with the prompt. Near-zero means the
 *       answer drifted off-topic; near-total means it parroted the question
 *       back. Both are bad, in opposite directions.</li>
 *   <li><b>Substance.</b> A two-word answer to a complex question is suspicious
 *       in a way a two-word answer to "what time do you close?" is not.</li>
 * </ul>
 *
 * <p>The raw score is not a probability. {@link CalibrationStore} turns it into
 * one using this tenant's own observed outcomes, because the relationship
 * between these signals and "actually needed the bigger model" is workload
 * specific — the same lesson UCCI (2026) reports for token-level confidence.
 */
@Component
public class DeferralJudge {

    /** Phrases where the model is reporting its own failure. */
    private static final Pattern HEDGE = Pattern.compile(
            "\\b(i (don't|do not|cannot|can't) (know|determine|find|access|verify)"
                    + "|i'm (not sure|unsure|unable)|i am (not sure|unsure|unable)"
                    + "|as an ai|i (don't|do not) have (access|enough|information)"
                    + "|unable to (answer|determine|provide)|no information (available|provided)"
                    + "|insufficient (information|context|data)"
                    + "|it('s| is) (unclear|not clear)|cannot be determined)\\b",
            Pattern.CASE_INSENSITIVE);

    /** The prompt asking for a machine-readable answer. */
    private static final Pattern WANTS_JSON = Pattern.compile(
            "\\b(json|as an? (json )?object|valid json|json schema)\\b", Pattern.CASE_INSENSITIVE);

    /** "in exactly N bullets", "N bullet points", "list N" — a countable demand. */
    private static final Pattern WANTS_N_ITEMS = Pattern.compile(
            "\\b(exactly |at most |no more than )?(\\d{1,2})\\s*(bullet|bullets|points?|items?|steps?)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A verdict, with the reasons that produced it. */
    public record Verdict(double rawScore, List<String> concerns, Map<String, Double> signals) {

        public boolean clean() {
            return concerns.isEmpty();
        }

        /** One-line explanation, suitable for an API field. */
        public String summary() {
            return concerns.isEmpty() ? "no concerns" : String.join("; ", concerns);
        }
    }

    /**
     * Scores an answer in [0,1], where 1 means "nothing looks wrong".
     *
     * @param request  the canonical request, for the instructions and context
     * @param answer   what the cheap tier produced
     * @param complexity the estimated task complexity, 0–1
     */
    public Verdict judge(LlmRequest request, String answer, double complexity) {
        Map<String, Double> signals = new LinkedHashMap<>();
        List<String> concerns = new ArrayList<>();

        if (answer == null || answer.isBlank()) {
            signals.put("empty", 0.0);
            concerns.add("empty answer");
            return new Verdict(0.0, concerns, signals);
        }

        String prompt = promptText(request);
        String trimmed = answer.strip();

        // --- hedging -------------------------------------------------------
        // Weighted hardest: a model saying it cannot answer is not a borderline
        // case, and no other signal disagrees usefully.
        double hedging = HEDGE.matcher(trimmed).find() ? 0.0 : 1.0;
        signals.put("noHedging", hedging);
        if (hedging == 0.0) {
            concerns.add("the model said it could not answer");
        }

        // --- truncation ----------------------------------------------------
        double complete = endsCleanly(trimmed) ? 1.0 : 0.0;
        signals.put("complete", complete);
        if (complete == 0.0) {
            concerns.add("answer stops mid-sentence");
        }

        // --- format --------------------------------------------------------
        double format = 1.0;
        if (WANTS_JSON.matcher(prompt).find() && !looksLikeJson(trimmed)) {
            format = 0.0;
            concerns.add("JSON was requested and the answer is not JSON");
        }
        signals.put("format", format);

        // --- explicit item count -------------------------------------------
        double counted = 1.0;
        var m = WANTS_N_ITEMS.matcher(prompt);
        if (m.find()) {
            boolean exact = m.group(1) != null && m.group(1).toLowerCase(Locale.ROOT).startsWith("exactly");
            int wanted = Integer.parseInt(m.group(2));
            int got = countItems(trimmed);
            // A count that is present and wrong is always a violation. A count of
            // zero is only a violation when the prompt said "exactly" — otherwise
            // an answer in prose may be a legitimate reading of the request, and
            // escalating on it would be noise.
            if ((got > 0 || exact) && got != wanted) {
                counted = 0.0;
                concerns.add(wanted + " items requested, " + got + " produced");
            }
        }
        signals.put("itemCount", counted);

        // --- grounding -----------------------------------------------------
        // Cosine against the prompt. Both extremes are failures: no overlap is
        // an answer to a different question, total overlap is the question
        // restated.
        double overlap = TextVectors.cosine(prompt, trimmed);
        double grounding = overlap < 0.04 ? 0.0 : overlap > 0.85 ? 0.2 : 1.0;
        signals.put("grounding", grounding);
        if (grounding == 0.0) {
            concerns.add("answer shares almost nothing with the question");
        } else if (grounding < 1.0) {
            concerns.add("answer largely restates the question");
        }

        // --- substance ------------------------------------------------------
        // Scaled by complexity: brevity is only suspicious when the question was
        // not itself simple.
        int words = trimmed.split("\\s+").length;
        double expected = 12 + 60 * complexity;
        double substance = words >= expected ? 1.0 : Math.max(0.0, words / expected);
        signals.put("substance", substance);
        if (substance < 0.35) {
            concerns.add("answer is short for a question this involved");
        }

        // Hard signals gate; soft signals scale. An answer that hedges or fails
        // a format contract cannot be rescued by being long and on topic.
        double hard = hedging * complete * format * counted;
        double soft = 0.55 * grounding + 0.45 * substance;
        double raw = hard * soft;

        return new Verdict(raw, concerns, signals);
    }

    private static String promptText(LlmRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request != null && request.messages() != null) {
            for (Message msg : request.messages()) {
                if (msg.content() != null) {
                    sb.append(' ').append(msg.content());
                }
            }
        }
        return sb.toString();
    }

    /**
     * Whether the answer reaches a natural stopping point. Closing punctuation,
     * a closing brace or bracket, or a trailing list item all count; a bare word
     * does not.
     */
    private static boolean endsCleanly(String s) {
        char last = s.charAt(s.length() - 1);
        return ".!?\"')]}`…:".indexOf(last) >= 0 || Character.isDigit(last);
    }

    private static boolean looksLikeJson(String s) {
        String t = s.strip();
        // Fenced blocks are the common shape when a model is asked for JSON.
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
            t = t.strip();
        }
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }

    /** Counts bullet or numbered list items, whichever the answer used. */
    private static int countItems(String s) {
        int bullets = 0;
        int numbered = 0;
        for (String line : s.split("\\R")) {
            String t = line.strip().toLowerCase(Locale.ROOT);
            if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ")) {
                bullets++;
            } else if (t.matches("^\\d{1,2}[.)]\\s+.*")) {
                numbered++;
            }
        }
        return Math.max(bullets, numbered);
    }
}
