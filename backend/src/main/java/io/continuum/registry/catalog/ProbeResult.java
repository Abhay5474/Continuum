package io.continuum.registry.catalog;

import java.util.Map;

/**
 * What one minimal chat request to a model showed.
 *
 * <p>This is how "free" is established without trusting a label: the key is a
 * free-tier key, and the model either answered it or it did not.
 */
public record ProbeResult(Outcome outcome, String detail, Map<String, Object> limits) {

    public enum Outcome {
        /** Answered. On a free-tier key, that is what free means. */
        CALLABLE,
        /** The provider's quota for this model on this key is zero: not on the free tier. */
        NOT_FREE,
        /** Listed, but this key may not call it (access, region, terms not accepted). */
        NO_ACCESS,
        /** Refused a minimal chat request for another reason. */
        REJECTED,
        /** Busy right now. Says nothing about the model; try again another time. */
        RATE_LIMITED,
        /** The key itself was refused. Stop testing this provider for this run. */
        KEY_REJECTED,
        /** Timeout or server error. Says nothing about the model. */
        TRANSIENT
    }

    public static ProbeResult of(Outcome outcome, String detail) {
        return new ProbeResult(outcome, detail, Map.of());
    }

    /** Outcomes that are facts about the model rather than about this moment. */
    public boolean conclusive() {
        return outcome != Outcome.RATE_LIMITED && outcome != Outcome.TRANSIENT && outcome != Outcome.KEY_REJECTED;
    }
}
