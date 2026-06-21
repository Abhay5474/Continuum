package io.continuum.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, deterministic semantic comparison of two AI outputs across five
 * dimensions: lexical similarity, decision-intent agreement, tool-selection
 * consistency, structured-output compatibility and constraint preservation.
 *
 * Kept free of Spring/IO so the comparison contract is unit-testable. An
 * optional LLM-judge augmentation lives in {@link SemanticReplayVerifier}.
 */
@Component
public class SemanticComparator {

    private final ObjectMapper mapper;

    public SemanticComparator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SemanticComparisonResult compare(String historical, String fresh, ReplayVerificationPolicy policy) {
        return compare(historical, fresh, List.of(), List.of(), policy);
    }

    public SemanticComparisonResult compare(String historical, String fresh,
                                            List<String> historicalTools, List<String> freshTools,
                                            ReplayVerificationPolicy policy) {
        double similarity = TextVectors.cosine(historical, fresh);

        DecisionPolarity.IntentResult intent = DecisionPolarity.consistency(historical, fresh);
        double intentScore = intent.score(); // -1 if N/A

        double toolScore = toolConsistency(historicalTools, freshTools);
        double structuredScore = structuredCompatibility(historical, fresh);
        double constraintScore = DecisionPolarity.salientTokenPreservation(historical, fresh);

        Weighted w = new Weighted();
        w.add(similarity, policy.wSimilarity());
        w.add(intentScore, policy.wIntent());
        w.add(toolScore, policy.wTool());
        w.add(structuredScore, policy.wStructured());
        w.add(constraintScore, policy.wConstraint());
        double overall = w.value();

        boolean intentHardFail = intentScore >= 0 && intentScore < policy.intentHardFailBelow();
        boolean passed = overall >= policy.passThreshold() && !intentHardFail;

        String explanation = (intentHardFail ? "INTENT REVERSAL: " + intent.explanation() + "; " : "")
                + "similarity=" + round(similarity)
                + (intentScore >= 0 ? ", intent=" + round(intentScore) : ", intent=n/a")
                + (toolScore >= 0 ? ", tools=" + round(toolScore) : ", tools=n/a")
                + (structuredScore >= 0 ? ", structured=" + round(structuredScore) : ", structured=n/a")
                + (constraintScore >= 0 ? ", constraints=" + round(constraintScore) : ", constraints=n/a");

        return new SemanticComparisonResult(similarity, intentScore, toolScore, structuredScore,
                constraintScore, overall, passed, "lexical", explanation);
    }

    private double toolConsistency(List<String> a, List<String> b) {
        if ((a == null || a.isEmpty()) && (b == null || b.isEmpty())) {
            return -1.0; // no tools on either side
        }
        if (a == null || b == null) {
            return 0.0;
        }
        return TextVectors.jaccard(String.join(" ", a), String.join(" ", b));
    }

    /** If both outputs are JSON objects, score field-name and value-type overlap. */
    private double structuredCompatibility(String historical, String fresh) {
        JsonNode h = tryJson(historical);
        JsonNode f = tryJson(fresh);
        if (h == null || f == null || !h.isObject() || !f.isObject()) {
            return -1.0; // not structured — N/A
        }
        Map<String, String> ht = typeMap(h);
        Map<String, String> ft = typeMap(f);
        if (ht.isEmpty() && ft.isEmpty()) {
            return 1.0;
        }
        java.util.Set<String> union = new java.util.HashSet<>(ht.keySet());
        union.addAll(ft.keySet());
        int compatible = 0;
        for (String key : union) {
            String t1 = ht.get(key);
            String t2 = ft.get(key);
            if (t1 != null && t1.equals(t2)) {
                compatible++; // present in both with same type
            }
        }
        return union.isEmpty() ? 1.0 : (double) compatible / union.size();
    }

    private Map<String, String> typeMap(JsonNode obj) {
        Map<String, String> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            out.put(e.getKey(), e.getValue().getNodeType().name());
        }
        return out;
    }

    private JsonNode tryJson(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return null;
        }
        try {
            return mapper.readTree(trimmed);
        } catch (Exception e) {
            return null;
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Weighted mean that ignores not-applicable (-1) dimensions. */
    private static final class Weighted {
        private double sum = 0;
        private double weight = 0;

        void add(double score, double w) {
            if (score < 0 || w <= 0) {
                return;
            }
            sum += score * w;
            weight += w;
        }

        double value() {
            return weight == 0 ? 0 : sum / weight;
        }
    }
}
