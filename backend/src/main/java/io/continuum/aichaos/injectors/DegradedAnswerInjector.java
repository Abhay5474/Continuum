package io.continuum.aichaos.injectors;

import org.springframework.stereotype.Component;

/**
 * Replaces an output with the kind of answer a quietly failing model gives:
 * vague, off the subject, and cut off before it finishes.
 *
 * <p>The other injectors keep an answer well formed: a hallucination flips a
 * decision word, drift swaps synonyms. A quality score cannot tell those from a
 * good answer, so they never move the semantic breaker. This one is what a
 * model degrading in production looks like to the quality gate, which is the
 * failure the breaker exists to catch.
 */
@Component
public class DegradedAnswerInjector {

    /**
     * Words chosen to share nothing with an ordinary question, and no closing
     * full stop, so the answer reads as off topic and unfinished.
     */
    static final String FILLER = "[DEGRADED] Generally speaking, seasonal weather patterns "
            + "across coastal plateaus tend to vary, and migratory birds usually";

    public String degrade(String content) {
        return content == null ? null : FILLER;
    }
}
