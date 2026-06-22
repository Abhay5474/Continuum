package io.continuum.gateway;

import io.continuum.registry.ModelCapabilities;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the ordered (provider, model) fallback chain for a request — the heart
 * of Features 4, 5 and 7.
 *
 * Deterministic scoring (no LLM in the loop):
 *  - capability filter (e.g. vision) removes incompatible models;
 *  - an explicitly requested model is pinned first, then used as fallback head;
 *  - providers are ordered by the (measured) provider rank passed in;
 *  - within that, complex tasks prefer stronger models, simple tasks prefer
 *    cheaper ones; health breaks ties.
 *
 * Pure function of its inputs, so routing correctness is unit-testable.
 */
@Component
public class ModelFallbackPolicy {

    public record ModelOption(String provider, String model, ModelCapabilities caps,
                              int providerRank, double healthScore) {
    }

    public record ModelCandidate(String provider, String model) {
    }

    public List<ModelCandidate> buildChain(List<ModelOption> options, double complexity,
                                           String requestedModel, boolean requireVision) {
        List<ModelOption> filtered = new ArrayList<>();
        for (ModelOption o : options) {
            if (requireVision && !o.caps().vision()) {
                continue;
            }
            filtered.add(o);
        }

        boolean complex = complexity >= 0.5;
        Comparator<ModelOption> cmp = Comparator
                .comparingInt(ModelOption::providerRank)                 // measured provider preference first
                .thenComparing(o -> pref(o.caps(), complex), Comparator.reverseOrder())
                .thenComparing(ModelOption::healthScore, Comparator.reverseOrder());
        filtered.sort(cmp);

        List<ModelCandidate> chain = new ArrayList<>();
        // Pin an explicitly requested model to the front if present.
        if (requestedModel != null) {
            filtered.stream().filter(o -> o.model().equals(requestedModel)).findFirst()
                    .ifPresent(o -> chain.add(new ModelCandidate(o.provider(), o.model())));
        }
        for (ModelOption o : filtered) {
            ModelCandidate c = new ModelCandidate(o.provider(), o.model());
            if (!chain.contains(c)) {
                chain.add(c);
            }
        }
        return chain;
    }

    /** Higher is more preferred. Complex → strength dominates; simple → cheapness dominates. */
    private double pref(ModelCapabilities caps, boolean complex) {
        double strength = strengthOf(caps.tier());
        double costPer1k = caps.costInputPer1k();
        if (complex) {
            return strength - costPer1k * 100;          // strongest first, mild cost tiebreak
        }
        return -costPer1k * 1000 + strength * 0.01;     // cheapest first, mild strength tiebreak
    }

    private double strengthOf(String tier) {
        if (tier == null) {
            return 1;
        }
        return switch (tier.toLowerCase()) {
            case "flagship", "pro", "versatile" -> 3;
            case "flash" -> 2;
            case "instant", "lite", "flash-lite" -> 1;
            case "mock" -> 0;
            default -> 1;
        };
    }
}
