package io.continuum.aichaos.injectors;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces an output with a plausible-looking but WRONG one, reversing any
 * decision it expresses. This is detectable by Extension 1's intent check, which
 * is exactly the point: it lets us measure whether downstream logic and replay
 * verification catch a hallucinated reversal.
 *
 * Replacement is single-pass (one regex scan) so opposing terms are not flipped
 * back and forth — each match is rewritten exactly once.
 */
@Component
public class HallucinationInjector {

    private static final Map<String, String> FLIP = new LinkedHashMap<>();
    private static final Pattern PATTERN;

    static {
        FLIP.put("low risk", "high risk");
        FLIP.put("high risk", "low risk");
        FLIP.put("approved", "rejected");
        FLIP.put("rejected", "approved");
        FLIP.put("approve", "reject");
        FLIP.put("reject", "approve");
        FLIP.put("legitimate", "fraudulent");
        FLIP.put("fraudulent", "legitimate");
        FLIP.put("safe", "dangerous");
        PATTERN = Pattern.compile("(?i)(" + String.join("|", FLIP.keySet().stream()
                .map(Pattern::quote).toList()) + ")");
    }

    public String corrupt(String content) {
        if (content == null) {
            return null;
        }
        Matcher m = PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = FLIP.get(m.group(1).toLowerCase());
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement == null ? m.group(1) : replacement));
        }
        m.appendTail(sb);
        return "[HALLUCINATED] " + sb;
    }
}
