package io.continuum.memory;

import io.continuum.semantic.TextVectors;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks candidate memories for a query by blending three measured signals:
 * lexical relevance (cosine), author salience, and recency decay. Pure and
 * deterministic — unit tested — so retrieval quality is reproducible.
 */
@Component
public class RelevanceRanker {

    private static final double W_RELEVANCE = 0.6;
    private static final double W_SALIENCE = 0.2;
    private static final double W_RECENCY = 0.2;

    public record Candidate(long id, String content, double salience, long ageMillis) {
    }

    public record Ranked(long id, double score, double relevance, double recency) {
    }

    public List<Ranked> rank(String query, List<Candidate> candidates) {
        Map<String, Integer> q = stemmed(query);
        return candidates.stream()
                .map(c -> {
                    double relevance = TextVectors.cosine(q, stemmed(c.content()));
                    double recency = recencyDecay(c.ageMillis());
                    double score = W_RELEVANCE * relevance
                            + W_SALIENCE * clamp(c.salience())
                            + W_RECENCY * recency;
                    return new Ranked(c.id(), score, relevance, recency);
                })
                .sorted(Comparator.comparingDouble(Ranked::score).reversed())
                .toList();
    }

    /**
     * Term frequencies with each word reduced to a rough stem. Exact words
     * missed the obvious: asked about a "refund", the memory "prefers refunds"
     * shared nothing with the question. Kept to memory ranking; the other users
     * of {@link TextVectors} have thresholds tuned to exact words.
     */
    static Map<String, Integer> stemmed(String text) {
        Map<String, Integer> out = new HashMap<>();
        TextVectors.termFrequency(text).forEach((w, n) -> out.merge(stem(w), n, Integer::sum));
        return out;
    }

    static String stem(String word) {
        String w = suffixless(word);
        // "damage" and "damaged" meet at "damag", "store" and "stores" at "stor".
        return w.length() > 4 && w.endsWith("e") ? w.substring(0, w.length() - 1) : w;
    }

    private static String suffixless(String w) {
        if (w.length() > 4 && w.endsWith("ies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.length() > 5 && w.endsWith("ing")) {
            return w.substring(0, w.length() - 3);
        }
        if (w.length() > 4 && w.endsWith("ed") && !w.endsWith("eed")) {
            return w.substring(0, w.length() - 2);
        }
        if (w.length() > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") || w.endsWith("sses"))) {
            return w.substring(0, w.length() - 2);
        }
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    /** Half-life style decay: ~1.0 when fresh, 0.5 at 24h, approaching 0 when old. */
    private double recencyDecay(long ageMillis) {
        double ageHours = Math.max(0, ageMillis) / 3_600_000.0;
        return 1.0 / (1.0 + ageHours / 24.0);
    }

    private double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
