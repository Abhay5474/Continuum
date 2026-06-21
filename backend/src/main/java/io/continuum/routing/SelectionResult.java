package io.continuum.routing;

import java.util.List;

/** The outcome of a provider selection: the ordered chain to try, plus the full scoring rationale. */
public record SelectionResult(
        RoutingMode mode,
        double complexity,
        int approxPromptTokens,
        List<String> chosenChain,
        List<ProviderScore> scores,
        String explanation) {

    public String chosenProvider() {
        return chosenChain.isEmpty() ? null : chosenChain.get(0);
    }
}
