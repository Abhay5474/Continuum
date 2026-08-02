package io.continuum.context;

import io.continuum.compression.PromptCompressor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the transformation cost and saved, in the only unit that matters.
 *
 * <p>Measured rather than claimed. "Structured JSON is better" is an assumption
 * that is often false — a naive JSON serialisation of a spreadsheet is usually
 * <em>larger</em> than the CSV it came from, because every value acquires a key.
 * A context layer that does not measure its own output will confidently make
 * prompts worse.
 *
 * <p>Token counts come from {@link PromptCompressor#estimateTokens}, which is
 * the same estimator the compression budget uses. It is an approximation —
 * four characters to a token — and it is deliberately the <em>same</em>
 * approximation, so the two features' numbers can be compared.
 *
 * @param before tokens the raw input would have cost, rendered as text
 * @param after  tokens the canonical rendering costs
 */
public record TokenStats(int before, int after) {

    public static TokenStats of(String rawText, String rendered) {
        return new TokenStats(PromptCompressor.estimateTokens(rawText),
                PromptCompressor.estimateTokens(rendered));
    }

    /** Negative when the transformation made the prompt larger, which happens. */
    public double reduction() {
        return before <= 0 ? 0 : (before - after) / (double) before;
    }

    public boolean improved() {
        return after < before;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("before", before);
        m.put("after", after);
        m.put("saved", before - after);
        m.put("reduction", reduction());
        m.put("improved", improved());
        return m;
    }
}
