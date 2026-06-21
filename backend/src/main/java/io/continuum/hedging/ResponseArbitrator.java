package io.continuum.hedging;

import io.continuum.provider.model.LlmResponse;
import org.springframework.stereotype.Component;

/**
 * Decides which of several racing provider responses to accept. Policy: accept
 * the first successful response to arrive; ignore failures (they let the executor
 * launch the next provider). Kept separate so the acceptance rule is explicit and
 * could evolve (e.g. quality-weighted arbitration) without touching the executor.
 */
@Component
public class ResponseArbitrator {

    public boolean accept(Attempt attempt) {
        return attempt.ok() && attempt.response() != null;
    }

    public record Attempt(String provider, boolean ok, LlmResponse response, Exception error, long latencyMs) {
        public static Attempt success(String provider, LlmResponse r, long ms) {
            return new Attempt(provider, true, r, null, ms);
        }

        public static Attempt failure(String provider, Exception e, long ms) {
            return new Attempt(provider, false, null, e, ms);
        }
    }
}
