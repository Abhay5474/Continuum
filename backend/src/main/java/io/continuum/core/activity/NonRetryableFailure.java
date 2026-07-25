package io.continuum.core.activity;

/**
 * Marks a failure the engine must not retry.
 *
 * <p>Retrying is the right default for a timeout or a 5xx — the condition is
 * usually transient. It is the wrong response to a rejected request: a 400 will
 * be a 400 on every attempt, so retrying only delays the failure and multiplies
 * load on the caller's endpoint. An activity throws a marked exception to say
 * the outcome is settled, and the engine fails the step immediately.
 */
public interface NonRetryableFailure {
}
