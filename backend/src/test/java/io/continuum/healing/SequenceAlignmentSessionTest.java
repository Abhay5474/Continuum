package io.continuum.healing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit contract of the stack-to-history diffing engine: identity when aligned,
 * correct classification of insertions / deletions / reorders, and deterministic
 * reuse of persisted ledger mappings.
 */
class SequenceAlignmentSessionTest {

    private SequenceAlignmentSession session(TreeMap<Long, String> types, Set<Long> used,
                                             List<AlignmentMapping> persisted) {
        return new SequenceAlignmentSession(types, used, persisted);
    }

    @Test
    void alignedHistoryIsPureIdentityWithZeroMappings() {
        TreeMap<Long, String> types = new TreeMap<>();
        types.put(2L, "A");
        types.put(3L, "B");
        SequenceAlignmentSession s = session(types, Set.of(1L, 2L, 3L), List.of());

        assertEquals(1L, s.alignSideEffect(1));
        assertEquals(2L, s.alignActivity(2, "A"));
        assertEquals(3L, s.alignActivity(3, "B"));
        assertEquals(4L, s.alignActivity(4, "C"), "new tail work keeps identity numbering");
        assertFalse(s.diverged(), "aligned replay must not write any healing state");
    }

    @Test
    void insertedActivityGetsFreshVirtualizedSlotAndLaterStepsRealign() {
        // History from old code A,B (both scheduled at 1,2). New code: A, X, B.
        TreeMap<Long, String> types = new TreeMap<>();
        types.put(1L, "A");
        types.put(2L, "B");
        SequenceAlignmentSession s = session(types, Set.of(1L, 2L), List.of());

        assertEquals(1L, s.alignActivity(1, "A"));
        long xSeq = s.alignActivity(2, "X");
        assertEquals(3L, xSeq, "inserted activity is virtualized past all used sequences");
        assertEquals(2L, s.alignActivity(3, "B"), "B realigns back to its recorded result");

        assertEquals(1, s.newMappings().size());
        AlignmentMapping m = s.newMappings().get(0);
        assertEquals(HealingResolutionType.INSERTION_MAPPED, m.resolutionType());
        assertEquals(2L, m.codeSeq());
        assertEquals(3L, m.historySeq());
    }

    @Test
    void deletedActivityIsSkippedWithoutReexecution() {
        // History from old code A,B,C. New code: A, C.
        TreeMap<Long, String> types = new TreeMap<>();
        types.put(1L, "A");
        types.put(2L, "B");
        types.put(3L, "C");
        SequenceAlignmentSession s = session(types, Set.of(1L, 2L, 3L), List.of());

        assertEquals(1L, s.alignActivity(1, "A"));
        assertEquals(3L, s.alignActivity(2, "C"), "cursor shifts past the obsolete B record");
        assertEquals(HealingResolutionType.DELETION_SKIPPED, s.newMappings().get(0).resolutionType());
        assertEquals(4L, s.alignActivity(3, "D"), "post-deletion tail keeps shifted numbering");
    }

    @Test
    void reorderedActivitiesReuseRecordedResults() {
        // History from old code A,B. New code: B, A.
        TreeMap<Long, String> types = new TreeMap<>();
        types.put(1L, "A");
        types.put(2L, "B");
        SequenceAlignmentSession s = session(types, Set.of(1L, 2L), List.of());

        assertEquals(2L, s.alignActivity(1, "B"));
        assertEquals(1L, s.alignActivity(2, "A"), "reorder aligns back to the unconsumed record");
        assertEquals(2, s.newMappings().size());
        assertEquals(HealingResolutionType.REORDER_ALIGNED, s.newMappings().get(1).resolutionType());
    }

    @Test
    void persistedLedgerMappingsShortCircuitTheDiffDeterministically() {
        TreeMap<Long, String> types = new TreeMap<>();
        types.put(1L, "A");
        types.put(2L, "B");
        types.put(3L, "X"); // the previously-virtualized insertion, now recorded
        AlignmentMapping ledger = new AlignmentMapping(2L, 3L,
                HealingResolutionType.INSERTION_MAPPED, -1L, "X");
        SequenceAlignmentSession s = session(types, Set.of(1L, 2L, 3L), List.of(ledger));

        assertEquals(1L, s.alignActivity(1, "A"));
        assertEquals(3L, s.alignActivity(2, "X"), "ledger mapping reused verbatim");
        assertEquals(2L, s.alignActivity(3, "B"), "delta restored from the ledger");
        assertFalse(s.diverged(), "replaying a healed instance produces no new mappings");
    }

    @Test
    void shiftedSideEffectCollidingWithActivitySlotIsVirtualized() {
        // Deletion shifts delta so a side effect would land on an activity slot.
        TreeMap<Long, String> types = new TreeMap<>();
        types.put(1L, "A");
        types.put(2L, "B");
        types.put(3L, "C");
        types.put(4L, "D");
        SequenceAlignmentSession s = session(types, Set.of(1L, 2L, 3L, 4L), List.of());

        assertEquals(1L, s.alignActivity(1, "A"));
        assertEquals(3L, s.alignActivity(2, "C")); // B deleted, delta becomes +1
        long se = s.alignSideEffect(3);            // candidate 4 is activity D's slot
        assertEquals(5L, se, "side effect avoids colliding with a recorded activity slot");
    }
}
