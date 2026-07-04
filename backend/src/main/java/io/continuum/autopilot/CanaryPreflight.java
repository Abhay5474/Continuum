package io.continuum.autopilot;

/**
 * Optional pre-flight gate consulted before a canary starts. V4 ships no
 * implementation (canaries start exactly as before); God Mode contributes a
 * digital-twin implementation that vetoes candidates which confidently regress
 * in offline replay. Absent bean or non-veto result ⇒ V4 behavior unchanged.
 */
public interface CanaryPreflight {

    record Result(boolean veto, String reason, double confidence) {
        public static Result allow() {
            return new Result(false, "no pre-flight objection", 0);
        }
    }

    Result check(String developerId, Long candidateBundleId);
}
