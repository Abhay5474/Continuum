package io.continuum.core.workflow;

/**
 * Translates the workflow code's command sequence into the history sequence the
 * replay should read/write, bridging structural drift between deployed code and
 * the append-only event log (inserted, deleted or re-ordered activities).
 *
 * The {@link #IDENTITY} aligner is a no-op: it preserves the exact pre-healing
 * replay semantics, so every path that does not opt into healing behaves as
 * before. Implementations must be deterministic: given the same history and the
 * same persisted resolution ledger, every replay must produce identical
 * translations.
 */
public interface ReplayAligner {

    /**
     * Align an activity command. Called once per {@code executeActivity} in code
     * order; returns the history sequence whose recorded outcome (or pending
     * schedule) this call should use. May allocate a fresh, unused sequence when
     * the call corresponds to an activity inserted by a new code deployment.
     */
    long alignActivity(long codeSeq, String activityType);

    /** Align a side-effect command ({@code sideEffect}/{@code now}/{@code randomUuid}). */
    long alignSideEffect(long codeSeq);

    /** Pass-through aligner — the exact historical replay behavior. */
    ReplayAligner IDENTITY = new ReplayAligner() {
        @Override
        public long alignActivity(long codeSeq, String activityType) {
            return codeSeq;
        }

        @Override
        public long alignSideEffect(long codeSeq) {
            return codeSeq;
        }
    };
}
