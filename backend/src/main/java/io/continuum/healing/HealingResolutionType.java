package io.continuum.healing;

/** The three shapes of structural code-to-history divergence Continuum can heal. */
public enum HealingResolutionType {

    /**
     * The deployed code contains an activity that does not exist anywhere in the
     * recorded history: a fresh, unused history sequence is virtualized for it so
     * it can execute without colliding with existing records.
     */
    INSERTION_MAPPED,

    /**
     * The recorded history contains activities that the deployed code no longer
     * calls: the sequence cursor is shifted forward past the obsolete records,
     * whose results are orphaned (never returned, never re-executed).
     */
    DELETION_SKIPPED,

    /**
     * The deployed code calls an activity that exists in history at an earlier,
     * not-yet-consumed position: the call is aligned back to that record so the
     * already-recorded result is reused instead of re-executing.
     */
    REORDER_ALIGNED
}
