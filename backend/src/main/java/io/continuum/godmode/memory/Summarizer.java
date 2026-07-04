package io.continuum.godmode.memory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic extractive summarizer used for Working → Episodic consolidation.
 *
 * TextRank-style scoring without an LLM: sentences are ranked by the summed
 * corpus frequency of their informative terms, normalized by length, and the
 * top sentences are kept in original order. Deterministic input → deterministic
 * summary, so consolidation never introduces replay non-determinism, costs no
 * tokens, and is unit-testable.
 */
public final class Summarizer {

    private static final int MAX_SUMMARY_SENTENCES = 5;
    private static final int MIN_TERM_LENGTH = 4;

    public record Summary(String text, int inputTokens, int summaryTokens) {
        public double compressionRatio() {
            return inputTokens == 0 ? 1.0 : (double) summaryTokens / inputTokens;
        }
    }

    public Summary summarize(List<String> items) {
        String joined = String.join(" ", items);
        int inputTokens = estimateTokens(joined);
        List<String> sentences = splitSentences(joined);
        if (sentences.size() <= MAX_SUMMARY_SENTENCES) {
            return new Summary(joined.trim(), inputTokens, inputTokens);
        }

        Map<String, Integer> termFreq = new HashMap<>();
        for (String s : sentences) {
            for (String t : terms(s)) {
                termFreq.merge(t, 1, Integer::sum);
            }
        }

        // Score = mean informative-term frequency (favors central, dense sentences).
        double[] scores = new double[sentences.size()];
        for (int i = 0; i < sentences.size(); i++) {
            List<String> ts = terms(sentences.get(i));
            double sum = 0;
            for (String t : ts) {
                sum += termFreq.getOrDefault(t, 0);
            }
            scores[i] = ts.isEmpty() ? 0 : sum / ts.size();
        }

        // Keep the MAX_SUMMARY_SENTENCES highest-scoring sentences, in order.
        List<Integer> ranked = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            ranked.add(i);
        }
        ranked.sort((a, b) -> Double.compare(scores[b], scores[a]));
        boolean[] keep = new boolean[sentences.size()];
        for (int i = 0; i < MAX_SUMMARY_SENTENCES; i++) {
            keep[ranked.get(i)] = true;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            if (keep[i]) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(sentences.get(i).trim());
            }
        }
        String text = out.toString();
        return new Summary(text, inputTokens, estimateTokens(text));
    }

    /** Rough token estimate (chars/4) — the same heuristic providers use for budgeting. */
    public static int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }

    private static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("(?<=[.!?])\\s+")) {
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<String> terms(String sentence) {
        List<String> out = new ArrayList<>();
        for (String t : sentence.toLowerCase().split("[^a-z0-9]+")) {
            if (t.length() >= MIN_TERM_LENGTH) {
                out.add(t);
            }
        }
        return out;
    }
}
