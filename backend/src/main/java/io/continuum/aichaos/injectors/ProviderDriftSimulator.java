package io.continuum.aichaos.injectors;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Simulates a model version/behavior change by rewording an output WITHOUT
 * changing its decision. This produces measurable lexical/semantic drift (caught
 * by Extension 1's similarity score) while leaving intent intact — the realistic
 * "Gemini V1 vs V2 phrasing" scenario.
 */
@Component
public class ProviderDriftSimulator {

    private static final Map<String, String> SYNONYMS = Map.of(
            "high", "elevated",
            "customer", "client",
            "analysis", "assessment",
            "risk", "exposure",
            "report", "summary",
            "payment", "remittance");

    public String drift(String content) {
        if (content == null) {
            return null;
        }
        String result = content;
        for (Map.Entry<String, String> e : SYNONYMS.entrySet()) {
            result = result.replaceAll("(?i)\\b" + e.getKey() + "\\b", e.getValue());
        }
        return result + " (phrasing updated by model revision)";
    }
}
