package io.continuum.uncertainty;

import io.continuum.semantic.TextVectors;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Groups answers that mean the same thing.
 *
 * <p>This is the step that makes semantic entropy semantic. Farquhar et al.
 * (Nature, 2024) cluster sampled answers by <em>bidirectional entailment</em> and
 * take entropy over the resulting meaning classes rather than over token
 * sequences — because a model that says "thirty days" three times in three
 * different sentences is certain, and token-level entropy calls it uncertain.
 *
 * <p>Proper entailment needs an NLI model. Continuum cannot assume one is
 * reachable — the whole system is designed to run with no API keys — so this is
 * a deterministic approximation with a specific structure:
 *
 * <ol>
 *   <li><b>Claims first.</b> Numbers, dates, money, percentages and proper nouns
 *       are what factual answers actually assert. If two answers assert the same
 *       claims, they agree regardless of how differently they are phrased.</li>
 *   <li><b>Contradiction beats similarity.</b> If both answers make claims and
 *       those claims disagree, they are separate meanings <em>however</em>
 *       similar the surrounding prose is. "The window is 30 days" and "The
 *       window is 14 days" are lexically near-identical and semantically
 *       opposite; a cosine-only comparator merges them, which is exactly the
 *       failure that would make the whole measurement useless.</li>
 *   <li><b>Prose falls back to lexical similarity</b>, for answers that assert
 *       nothing countable.</li>
 * </ol>
 *
 * <p>The claim-first ordering is also what fixes the cascade's agreement test.
 * Comparing a terse answer to a verbose one by cosine reports disagreement
 * because the vocabularies differ; comparing their claims reports agreement,
 * which is the truth.
 */
@Component
public class AnswerClusterer {

    /** Numbers, money, percentages, dates — what a factual answer asserts. */
    private static final Pattern NUMERIC = Pattern.compile(
            "(?<!\\w)(?:[$£€]\\s?)?\\d[\\d,]*(?:\\.\\d+)?\\s?%?(?!\\w)");

    /** Number words, so "thirty days" and "30 days" assert the same thing. */
    private static final Map<String, String> NUMBER_WORDS = Map.ofEntries(
            Map.entry("zero", "0"), Map.entry("one", "1"), Map.entry("two", "2"),
            Map.entry("three", "3"), Map.entry("four", "4"), Map.entry("five", "5"),
            Map.entry("six", "6"), Map.entry("seven", "7"), Map.entry("eight", "8"),
            Map.entry("nine", "9"), Map.entry("ten", "10"), Map.entry("eleven", "11"),
            Map.entry("twelve", "12"), Map.entry("fourteen", "14"), Map.entry("fifteen", "15"),
            Map.entry("twenty", "20"), Map.entry("thirty", "30"), Map.entry("forty", "40"),
            Map.entry("fifty", "50"), Map.entry("sixty", "60"), Map.entry("ninety", "90"),
            Map.entry("hundred", "100"), Map.entry("thousand", "1000"));

    /** A capitalised word that is not merely sentence-initial. */
    private static final Pattern PROPER = Pattern.compile("(?<=[a-z,;:]\\s)([A-Z][a-zA-Z]{2,})");

    /** Prose with no claims is compared lexically; this is the bar for "same". */
    private static final double PROSE_SIMILARITY = 0.55;

    /** One meaning class: which samples fell into it, and one representative. */
    public record Cluster(List<Integer> members, String representative) {
        public int size() {
            return members.size();
        }
    }

    /**
     * Partitions answers into meaning classes.
     *
     * <p>Greedy single-link assignment: each answer joins the first cluster it
     * agrees with, or starts a new one. Single-link is the right choice here
     * because agreement is meant to be transitive — three phrasings of one fact
     * should collapse to one cluster even if the first and third share little
     * vocabulary directly.
     */
    public List<Cluster> cluster(List<String> answers) {
        List<Cluster> clusters = new ArrayList<>();
        if (answers == null || answers.isEmpty()) {
            return clusters;
        }
        List<List<Integer>> members = new ArrayList<>();
        List<String> reps = new ArrayList<>();

        for (int i = 0; i < answers.size(); i++) {
            String a = answers.get(i);
            int found = -1;
            for (int c = 0; c < reps.size() && found < 0; c++) {
                for (int idx : members.get(c)) {
                    if (sameMeaning(a, answers.get(idx))) {
                        found = c;
                        break;
                    }
                }
            }
            if (found >= 0) {
                members.get(found).add(i);
                // Keep the longest member as the representative: it is the one
                // most likely to carry the full answer for display.
                if (a != null && reps.get(found) != null && a.length() > reps.get(found).length()) {
                    reps.set(found, a);
                }
            } else {
                List<Integer> m = new ArrayList<>();
                m.add(i);
                members.add(m);
                reps.add(a);
            }
        }
        for (int c = 0; c < members.size(); c++) {
            clusters.add(new Cluster(members.get(c), reps.get(c)));
        }
        clusters.sort((x, y) -> Integer.compare(y.size(), x.size()));
        return clusters;
    }

    /**
     * Whether two answers assert the same thing.
     *
     * <p>Public because the cascade uses it in place of raw cosine: comparing a
     * terse answer to a verbose one lexically reports disagreement even when
     * both state the same fact.
     */
    public boolean sameMeaning(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        String x = a.strip();
        String y = b.strip();
        if (x.isEmpty() || y.isEmpty()) {
            return x.isEmpty() && y.isEmpty();
        }

        Set<String> ca = claims(x);
        Set<String> cb = claims(y);

        if (!ca.isEmpty() && !cb.isEmpty()) {
            // Both assert something. Disagreement on the assertions is a
            // contradiction, and no amount of shared prose makes it agreement.
            Set<String> shared = new LinkedHashSet<>(ca);
            shared.retainAll(cb);
            if (shared.isEmpty()) {
                return false;
            }
            // Jaccard over claims: near-total overlap is the same assertion,
            // partial overlap means one answer asserts things the other denies
            // or omits.
            Set<String> union = new LinkedHashSet<>(ca);
            union.addAll(cb);
            double jaccard = (double) shared.size() / union.size();
            return jaccard >= 0.6;
        }

        // Neither asserts anything countable, or only one does — compare prose.
        return TextVectors.cosine(x, y) >= PROSE_SIMILARITY;
    }

    /**
     * The assertions in an answer: normalised numbers and proper nouns.
     *
     * <p>Number words are folded to digits so "thirty days" and "30 days" are one
     * claim rather than two.
     */
    public Set<String> claims(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        Matcher n = NUMERIC.matcher(text);
        while (n.find()) {
            out.add(normaliseNumber(n.group()));
        }
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            String digits = NUMBER_WORDS.get(raw);
            if (digits != null) {
                out.add(digits);
            }
        }
        Matcher p = PROPER.matcher(text);
        while (p.find()) {
            out.add(p.group(1).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static String normaliseNumber(String raw) {
        String t = raw.strip().replaceAll("[,\\s]", "").replaceAll("^[$£€]", "");
        boolean pct = t.endsWith("%");
        if (pct) {
            t = t.substring(0, t.length() - 1);
        }
        // Trim a trailing ".0" so 30 and 30.0 are one claim.
        if (t.contains(".")) {
            t = t.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return pct ? t + "%" : t;
    }
}
