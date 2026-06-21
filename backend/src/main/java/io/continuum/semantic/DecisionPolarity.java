package io.continuum.semantic;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Detects the <em>decision polarity</em> expressed in an AI output and whether
 * two outputs agree on it.
 *
 * This is what separates a benign rewording ("high risk" vs "elevated risk")
 * from a dangerous reversal ("reject" vs "approve"). Lexical similarity alone
 * cannot catch reversals — the two texts are short and share structure — so we
 * additionally extract polarity from known opposing decision pairs.
 */
public final class DecisionPolarity {

    /** Each pair is {negative-pole-terms, positive-pole-terms}. */
    private static final List<String[][]> OPPOSING = List.of(
            new String[][]{{"reject", "rejected", "deny", "denied", "decline", "declined", "block", "blocked"},
                    {"approve", "approved", "accept", "accepted", "allow", "allowed", "grant", "granted"}},
            new String[][]{{"high risk", "elevated risk", "high-risk", "risky", "dangerous"},
                    {"low risk", "low-risk", "safe", "minimal risk"}},
            new String[][]{{"fail", "failed", "failure", "invalid", "incorrect"},
                    {"pass", "passed", "success", "successful", "valid", "correct"}},
            new String[][]{{"no", "negative", "false", "disagree"},
                    {"yes", "positive", "true", "agree"}},
            new String[][]{{"fraud", "fraudulent", "suspicious"},
                    {"legitimate", "genuine", "trusted"}},
            new String[][]{{"escalate", "urgent", "critical"},
                    {"resolved", "routine", "normal"}});

    private DecisionPolarity() {
    }

    /** -1 = negative pole, +1 = positive pole, 0 = no recognized decision. */
    public static int polarity(String text, int pairIndex) {
        if (text == null) {
            return 0;
        }
        String t = " " + text.toLowerCase(Locale.ROOT) + " ";
        String[][] pair = OPPOSING.get(pairIndex);
        boolean neg = containsAny(t, pair[0]);
        boolean pos = containsAny(t, pair[1]);
        if (neg && !pos) return -1;
        if (pos && !neg) return 1;
        return 0;
    }

    /**
     * Intent consistency in [0,1]:
     *  1.0 when every decision dimension agrees (or none is present),
     *  0.0 when any dimension is reversed.
     */
    public static IntentResult consistency(String historical, String fresh) {
        boolean anyDecision = false;
        boolean reversed = false;
        boolean agreed = false;
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < OPPOSING.size(); i++) {
            int h = polarity(historical, i);
            int f = polarity(fresh, i);
            if (h == 0 && f == 0) {
                continue;
            }
            anyDecision = true;
            if (h != 0 && f != 0) {
                if (h == f) {
                    agreed = true;
                } else {
                    reversed = true;
                    detail.append("reversed decision dimension #").append(i)
                            .append(" (").append(h).append("→").append(f).append("); ");
                }
            } else {
                // One side expresses a decision the other dropped — partial divergence.
                detail.append("decision dimension #").append(i).append(" present on only one side; ");
            }
        }
        if (!anyDecision) {
            return new IntentResult(true, false, -1.0, "no explicit decision detected");
        }
        if (reversed) {
            return new IntentResult(false, true, 0.0, detail.toString().trim());
        }
        double score = agreed ? 1.0 : 0.6; // present-on-one-side only => partial
        return new IntentResult(true, false, score, agreed ? "decisions agree" : detail.toString().trim());
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String n : needles) {
            // Multi-word phrases match as substrings; single words match on boundaries.
            if (n.contains(" ")) {
                if (haystack.contains(n)) {
                    return true;
                }
            } else if (haystack.matches(".*\\b" + java.util.regex.Pattern.quote(n) + "\\b.*")) {
                return true;
            }
        }
        return false;
    }

    /** Numeric/identifier tokens that should usually survive a faithful rerun. */
    public static double salientTokenPreservation(String historical, String fresh) {
        Set<String> h = numericTokens(historical);
        if (h.isEmpty()) {
            return -1.0; // not applicable
        }
        Set<String> f = numericTokens(fresh);
        long kept = h.stream().filter(f::contains).count();
        return (double) kept / h.size();
    }

    private static Set<String> numericTokens(String text) {
        if (text == null) {
            return Set.of();
        }
        java.util.HashSet<String> out = new java.util.HashSet<>();
        for (String tok : text.split("[^A-Za-z0-9.]+")) {
            if (tok.matches(".*\\d.*")) {
                out.add(tok.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    public record IntentResult(boolean consistent, boolean reversed, double score, String explanation) {
    }
}
