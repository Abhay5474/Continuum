package io.continuum.compression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V8 — LLMLingua-inspired prompt compression (Jiang et al., Microsoft Research,
 * EMNLP 2023; and LongLLMLingua, 2024).
 *
 * <p>LLMLingua's insight: a prompt carries far more tokens than the model needs;
 * dropping the <em>least informative</em> tokens shrinks input cost 2–20× with
 * little quality loss. The real paper measures token informativeness with a
 * small language model's perplexity. Running a GPU LM inside a gateway isn't
 * available here, so this is a faithful, deterministic <b>information-theoretic
 * approximation</b> of the same idea:
 *
 * <ul>
 *   <li><b>Coarse-to-fine (budget controller + token compressor):</b> like the
 *       paper, we work at two granularities — first rank sentences by
 *       information density and keep the densest under the budget, then within
 *       kept sentences prune low-information tokens.</li>
 *   <li><b>Self-information proxy:</b> token informativeness ≈ inverse corpus
 *       frequency (a rare, content-bearing word carries more bits than "the").
 *       Stopwords and fillers are the first to go.</li>
 *   <li><b>Budget + protected spans:</b> a target compression ratio bounds the
 *       output; numbers, IDs, code/JSON and quoted spans are never dropped
 *       (LongLLMLingua likewise protects salient content).</li>
 * </ul>
 *
 * Deterministic and local — no tokens spent, no replay non-determinism.
 */
public final class PromptCompressor {

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "and", "of", "to", "in", "on", "for", "with", "as", "at",
            "by", "is", "are", "was", "were", "be", "been", "being", "this", "that", "these", "those",
            "it", "its", "i", "you", "he", "she", "they", "we", "them", "his", "her", "their", "our",
            "so", "then", "there", "here",
            "please", "just", "very", "really", "actually", "basically", "simply", "quite", "kind",
            "sort", "like", "well", "okay", "ok", "um", "uh", "also", "too", "much", "many",
            "into", "from", "about", "again", "further", "once");

    /**
     * Words that change what a sentence means, kept however short or common.
     * The compressor used to drop every word of two letters or fewer and a
     * list that included "under", "over", "all", "any", "or", "but", "than"
     * and the question words — so "I have no allergies" reached the model as
     * "have allergies", and "over 18" and "under 18" became the same prompt.
     */
    private static final Set<String> MEANING = Set.of(
            "no", "not", "nor", "never", "none", "neither", "without", "nothing", "nobody",
            "if", "unless", "or", "but", "except", "only", "than", "under", "over", "above", "below",
            "before", "after", "all", "any", "some", "each", "every", "more", "less", "most", "least",
            "what", "when", "where", "who", "whom", "whose", "which", "why", "how",
            "up", "me", "my", "us", "do", "go");

    // Spans that must survive: quoted text, code/JSON blocks, numbers, IDs, emails, URLs.
    private static final Pattern PROTECTED = Pattern.compile(
            "```[\\s\\S]*?```" +                       // fenced code
            "|`[^`]+`" +                                // inline code
            "|\"[^\"]{0,200}\"" +                       // quoted strings
            "|\\{[^{}]*}" +                             // small JSON-ish objects
            "|\\b[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b" +   // emails
            "|https?://\\S+" +                          // URLs
            "|\\b[A-Z0-9]+(?:-[A-Z0-9]+)+\\b" +          // hyphenated codes (TXN-99A2B)
            "|\\b\\d[\\d,.]*\\b" +                       // numbers
            "|\\b[A-Z0-9]{4,}\\b" +                      // IDs / codes
            "|\\$\\d[\\d,.]*");                          // money
    // Case-sensitive on purpose. With CASE_INSENSITIVE the ID pattern matched
    // every word of four letters or more, so nearly all prose was "protected"
    // and nothing could be compressed.

    private static final String MARK_OPEN = "\uE000";
    private static final String MARK_CLOSE = "\uE001";

    public record Result(String text, int originalTokens, int compressedTokens,
                         double targetRatio, int protectedSpans) {
        public double achievedRatio() {
            return originalTokens == 0 ? 1.0 : (double) compressedTokens / originalTokens;
        }
    }

    /**
     * Compress {@code text} toward {@code targetRatio} (kept fraction, e.g. 0.5
     * keeps ~half the tokens). Protected spans are always preserved. Short
     * inputs (below {@code minTokens}) pass through untouched.
     */
    public Result compress(String text, double targetRatio, int minTokens) {
        int originalTokens = estimateTokens(text);
        if (text == null || text.isBlank() || originalTokens <= minTokens) {
            return new Result(text, originalTokens, originalTokens, targetRatio, 0);
        }
        double target = Math.max(0.15, Math.min(0.95, targetRatio));

        // 1. Extract and placeholder-protect salient spans.
        Map<String, String> protectedSpans = new HashMap<>();
        StringBuilder masked = new StringBuilder();
        Matcher m = PROTECTED.matcher(text);
        int last = 0;
        int pid = 0;
        while (m.find()) {
            masked.append(text, last, m.start());
            // Private-use characters, not control characters: String.trim() strips
            // everything up to U+0020, and a placeholder at the start of a
            // sentence lost its leading NUL there — so it was never restored and
            // reached the model as "P1".
            String key = MARK_OPEN + "P" + (pid++) + MARK_CLOSE;
            protectedSpans.put(key, m.group());
            masked.append(' ').append(key).append(' ');
            last = m.end();
        }
        masked.append(text.substring(last));
        String working = masked.toString();

        // 2. Corpus term frequencies (for the self-information proxy).
        Map<String, Integer> freq = new HashMap<>();
        for (String t : working.toLowerCase().split("[^a-z0-9]+")) {
            if (!t.isBlank()) {
                freq.merge(t, 1, Integer::sum);
            }
        }

        // 3. Coarse pass — rank sentences by information density, keep densest under budget.
        List<String> sentences = splitSentences(working);
        int totalBudget = (int) Math.ceil(originalTokens * target);
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            scored.add(new Scored(i, sentences.get(i), sentenceScore(sentences.get(i), freq, protectedSpans)));
        }
        // Always keep any sentence containing a protected span or the last sentence (recency).
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        Set<Integer> keep = new HashSet<>();
        int used = 0;
        for (Scored s : scored) {
            int cost = estimateTokens(s.text);
            boolean mustKeep = containsProtected(s.text, protectedSpans) || s.index == sentences.size() - 1;
            if (mustKeep || used + cost <= totalBudget) {
                keep.add(s.index);
                used += cost;
            }
        }

        // 4. Fine pass — within kept sentences, drop low-information tokens, but
        // only while the budget is still exceeded.
        //
        // Pruning used to run unconditionally, which meant the target ratio was
        // a floor rather than a target: asked to keep 85% it would keep about
        // 75%, because stopword removal happens regardless of how much room is
        // left. That is harmless when the caller wants aggressive compression
        // and wrong when they deliberately asked for a gentle one — an
        // instruction block compressed harder than requested loses clauses the
        // budget was written to protect.
        int keptTokens = 0;
        for (int i = 0; i < sentences.size(); i++) {
            if (keep.contains(i)) {
                keptTokens += estimateTokens(sentences.get(i));
            }
        }
        int toRemove = keptTokens - totalBudget;

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            if (!keep.contains(i)) {
                continue;
            }
            String sentence = sentences.get(i);
            if (toRemove <= 0) {
                // Already within budget: taking more would be over-compression.
                out.append(sentence).append(' ');
                continue;
            }
            String pruned = pruneTokens(sentence, protectedSpans);
            toRemove -= estimateTokens(sentence) - estimateTokens(pruned);
            out.append(pruned).append(' ');
        }

        // 5. Restore protected spans.
        String compressed = out.toString().trim();
        for (Map.Entry<String, String> e : protectedSpans.entrySet()) {
            compressed = compressed.replace(e.getKey(), e.getValue());
        }
        compressed = compressed.replaceAll("\\s+", " ").trim();

        int compressedTokens = estimateTokens(compressed);
        // Safety: never emit something larger than the input.
        if (compressedTokens >= originalTokens) {
            return new Result(text, originalTokens, originalTokens, target, protectedSpans.size());
        }
        return new Result(compressed, originalTokens, compressedTokens, target, protectedSpans.size());
    }

    private double sentenceScore(String sentence, Map<String, Integer> freq, Map<String, String> prot) {
        if (containsProtected(sentence, prot)) {
            return Double.MAX_VALUE / 2; // salient content: keep
        }
        List<String> terms = contentTerms(sentence);
        if (terms.isEmpty()) {
            return 0;
        }
        double info = 0;
        for (String t : terms) {
            info += 1.0 / (freq.getOrDefault(t, 1)); // rarer term = more information
        }
        return info / Math.sqrt(terms.size()); // normalize, but favour dense sentences
    }

    /** Drop stopwords/fillers and low-information duplicates within one sentence. */
    private String pruneTokens(String sentence, Map<String, String> prot) {
        String[] tokens = sentence.split("\\s+");
        StringBuilder sb = new StringBuilder();
        Set<String> seenContent = new HashSet<>();
        for (String tok : tokens) {
            String lower = tok.toLowerCase().replaceAll("[^a-z0-9]", "");
            boolean isProtected = tok.contains(MARK_OPEN + "P");
            if (isProtected || lower.isEmpty()) {
                sb.append(tok).append(' ');
                continue;
            }
            if (MEANING.contains(lower) || tok.toLowerCase().matches(".*n['’]t\\W*")) {
                // A negation, a condition, a comparison or a question word:
                // never filler. (Contractions are matched on the raw token:
                // "don't" is "dont" once punctuation is stripped.) Repeats are kept too — "no, no" and
                // "not A or B, not C" both mean what they say.
                sb.append(tok).append(' ');
                continue;
            }
            if (STOPWORDS.contains(lower)) {
                continue; // filler — drop
            }
            if (lower.length() <= 2 && !lower.matches("\\d+")) {
                continue; // tiny non-numeric fragments carry little info
            }
            if (!seenContent.add(lower)) {
                continue; // immediate redundancy within the sentence
            }
            sb.append(tok).append(' ');
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? sentence.trim() : result;
    }

    private boolean containsProtected(String s, Map<String, String> prot) {
        return s.contains(MARK_OPEN + "P");
    }

    private List<String> contentTerms(String sentence) {
        List<String> out = new ArrayList<>();
        for (String t : sentence.toLowerCase().split("[^a-z0-9]+")) {
            if (t.length() >= 3 && !STOPWORDS.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("(?<=[.!?])\\s+|\\n+")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        if (out.isEmpty()) {
            out.add(text.trim());
        }
        return out;
    }

    /** Same chars/4 token estimate the rest of the system uses. */
    public static int estimateTokens(String text) {
        return text == null ? 0 : Math.max(0, text.length() / 4);
    }

    private static final class Scored {
        final int index;
        final String text;
        final double score;

        Scored(int index, String text, double score) {
            this.index = index;
            this.text = text;
            this.score = score;
        }
    }
}
