package io.continuum.provenance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One decision Continuum made about one request, and why.
 *
 * <p>Continuum already explains itself — in a string. {@code routingReason}
 * accumulates fragments like {@code "mode=BALANCED, complexity=0.00, low
 * complexity → cheaper model → mock/mock-small · quality 0.55 (repair) ·
 * confidence 0.82 over 4 samples"}. That is readable and useless: nothing can
 * aggregate it, alert on it, or answer "how often did the cascade escalate last
 * Tuesday, and what did it cost?"
 *
 * <p>The same facts recorded as data become a provenance graph — every decision,
 * its alternatives, and what it cost. The string stays, because a human reading
 * one response still wants a sentence.
 *
 * @param stage        which subsystem decided
 * @param choice       what it chose
 * @param reason       why, in words
 * @param alternatives what else was available and was not chosen
 * @param costDelta    what this decision added or saved, in dollars
 * @param latencyMs    what it added
 */
public record Decision(Stage stage, String choice, String reason, List<String> alternatives,
                       double costDelta, long latencyMs) {

    /**
     * The points on the request path where a real choice is made.
     *
     * <p>Named after what they decide rather than after the class that decides
     * it: a provenance record outlives the code that produced it, and a stage
     * called {@code ROUTE} still means something after the routing engine has
     * been rewritten.
     */
    public enum Stage {
        ADMISSION, CACHE, FIREWALL, CONTEXT, COMPLEXITY, ROUTE, PROVIDER, CASCADE,
        CONFIDENCE, QUALITY, REPAIR, BREAKER, OUTPUT
    }

    public static Decision of(Stage stage, String choice, String reason) {
        return new Decision(stage, choice, reason, List.of(), 0, 0);
    }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stage.name());
        m.put("choice", choice);
        m.put("reason", reason);
        m.put("alternatives", alternatives);
        m.put("costDelta", costDelta);
        m.put("latencyMs", latencyMs);
        return m;
    }

    /**
     * The same decision as an OpenTelemetry span, using the GenAI semantic
     * conventions where they exist.
     *
     * <p>Shaped for the tooling developers already run rather than for a
     * Continuum-specific viewer. An observability feature that can only be read
     * inside the product it observes has solved the easy half of the problem.
     */
    public Map<String, Object> asSpan() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("continuum.stage", stage.name().toLowerCase());
        attrs.put("continuum.choice", choice);
        attrs.put("continuum.reason", reason);
        if (!alternatives.isEmpty()) {
            attrs.put("continuum.alternatives", alternatives);
        }
        if (costDelta != 0) {
            attrs.put("gen_ai.usage.cost", costDelta);
        }
        if (stage == Stage.PROVIDER || stage == Stage.ROUTE) {
            attrs.put("gen_ai.request.model", choice);
        }
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("name", "continuum." + stage.name().toLowerCase());
        span.put("durationMs", latencyMs);
        span.put("attributes", attrs);
        return span;
    }
}
