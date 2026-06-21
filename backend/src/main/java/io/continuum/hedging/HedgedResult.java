package io.continuum.hedging;

import io.continuum.provider.model.LlmResponse;

import java.util.List;

/** Outcome of a hedged execution: the winning response plus what it took. */
public record HedgedResult(
        LlmResponse response,
        String winningProvider,
        boolean hedged,
        int requestsLaunched,
        long elapsedMs,
        List<String> attemptedProviders) {
}
