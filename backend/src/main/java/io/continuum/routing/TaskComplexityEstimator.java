package io.continuum.routing;

import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Estimates how hard a task is, in [0,1], from observable request features:
 * total prompt size and the presence of reasoning-heavy intent words. Used to
 * decide when a stronger (and costlier) model is worth it.
 *
 * Heuristic but real and deterministic — it reads the actual request, not a
 * made-up label.
 */
@Component
public class TaskComplexityEstimator {

    private static final String[] COMPLEX_SIGNALS = {
            "analyze", "analysis", "reason", "report", "explain", "evaluate", "compare",
            "design", "plan", "summarize", "synthesize", "derive", "prove", "debug", "diagnose"};

    public Estimate estimate(LlmRequest request) {
        int chars = 0;
        StringBuilder all = new StringBuilder();
        for (Message m : request.messages()) {
            if (m.content() != null) {
                chars += m.content().length();
                all.append(' ').append(m.content().toLowerCase(Locale.ROOT));
            }
        }
        int approxTokens = chars / 4;

        // Length component: saturates around ~8k tokens.
        double lengthScore = Math.min(1.0, approxTokens / 8000.0);

        // Intent component: density of reasoning-signal words.
        String text = all.toString();
        int hits = 0;
        for (String s : COMPLEX_SIGNALS) {
            if (text.contains(s)) {
                hits++;
            }
        }
        double intentScore = Math.min(1.0, hits / 4.0);

        double complexity = Math.min(1.0, 0.6 * lengthScore + 0.4 * intentScore);
        return new Estimate(complexity, approxTokens, lengthScore, intentScore);
    }

    public record Estimate(double complexity, int approxPromptTokens, double lengthScore, double intentScore) {
    }
}
