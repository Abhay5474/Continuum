package io.continuum.core.activity;

/**
 * A unit of non-deterministic, possibly-failing work: an LLM call, an email,
 * a payment, a database read.
 *
 * Activities may throw to signal failure; the engine will retry them according
 * to the scheduling workflow's {@link io.continuum.core.workflow.ActivityOptions}.
 * Their successful result is recorded as an {@code ACTIVITY_COMPLETED} event and
 * returned verbatim on replay, so activities are never re-executed during
 * recovery.
 */
public interface Activity {

    /** Stable identifier used by workflows to schedule this activity. */
    String type();

    /**
     * Execute the work. Returns any JSON-serializable result (or {@code null}).
     * Throwing triggers the retry/failover machinery.
     */
    Object execute(String inputJson, ActivityContext ctx) throws Exception;
}
