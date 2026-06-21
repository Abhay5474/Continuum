package io.continuum.semantic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, dependency-free text similarity primitives.
 *
 * Continuum cannot assume an embedding API (or network/keys) is available, so
 * semantic comparison and memory relevance ranking are built on real, offline
 * vector-space math: term-frequency vectors with cosine similarity, plus Jaccard
 * overlap. These are genuine measurable signals — not random or hardcoded — and
 * being deterministic they are unit-testable and replay-safe.
 *
 * When a real LLM provider IS configured, an optional LLM-as-judge path layers
 * on top of this (see {@link SemanticComparator}); this class is the always-on
 * floor.
 */
public final class TextVectors {

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "to", "of", "and", "or",
            "in", "on", "at", "for", "with", "as", "by", "this", "that", "it", "its", "has", "have",
            "had", "will", "would", "should", "can", "could", "may", "might", "do", "does", "did");

    private TextVectors() {
    }

    /** Lowercase, split on non-word chars, drop stopwords and 1-char tokens. */
    public static Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> tf = new HashMap<>();
        if (text == null || text.isBlank()) {
            return tf;
        }
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.length() < 2 || STOPWORDS.contains(raw)) {
                continue;
            }
            tf.merge(raw, 1, Integer::sum);
        }
        return tf;
    }

    /** Cosine similarity in [0,1] over term-frequency vectors. */
    public static double cosine(String a, String b) {
        return cosine(termFrequency(a), termFrequency(b));
    }

    public static double cosine(Map<String, Integer> a, Map<String, Integer> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return a.isEmpty() && b.isEmpty() ? 1.0 : 0.0;
        }
        long dot = 0;
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            Integer other = b.get(e.getKey());
            if (other != null) {
                dot += (long) e.getValue() * other;
            }
        }
        double normA = norm(a);
        double normB = norm(b);
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (normA * normB);
    }

    /** Jaccard overlap of the token sets in [0,1]. */
    public static double jaccard(String a, String b) {
        Set<String> sa = new HashSet<>(termFrequency(a).keySet());
        Set<String> sb = new HashSet<>(termFrequency(b).keySet());
        if (sa.isEmpty() && sb.isEmpty()) {
            return 1.0;
        }
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        if (union.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        return (double) inter.size() / union.size();
    }

    private static double norm(Map<String, Integer> v) {
        long sum = 0;
        for (int c : v.values()) {
            sum += (long) c * c;
        }
        return Math.sqrt(sum);
    }
}
