package io.continuum.healing;

/**
 * One entry of a workflow instance's structural resolution map: the code's
 * command sequence {@code codeSeq} is served by history sequence
 * {@code historySeq}, and every later command is translated with
 * {@code deltaAfter} (historySeq = codeSeq + delta) until the next mapping.
 *
 * Persisted as {@code virtualized_payload_json} in {@code workflow_healing_logs}
 * so every future replay of the instance applies the identical bridge.
 */
public record AlignmentMapping(long codeSeq,
                               long historySeq,
                               HealingResolutionType resolutionType,
                               long deltaAfter,
                               String activityType) {
}
