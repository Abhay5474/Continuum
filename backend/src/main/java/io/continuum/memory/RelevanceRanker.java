package io.continuum.memory;

import io.continuum.semantic.TextVectors;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

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
        return candidates.stream()
                .map(c -> {
                    double relevance = TextVectors.cosine(query, c.content());
                    double recency = recencyDecay(c.ageMillis());
                    double score = W_RELEVANCE * relevance
                            + W_SALIENCE * clamp(c.salience())
                            + W_RECENCY * recency;
                    return new Ranked(c.id(), score, relevance, recency);
                })
                .sorted(Comparator.comparingDouble(Ranked::score).reversed())
                .toList();
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
