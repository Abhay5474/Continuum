package io.continuum.healing;

import io.continuum.core.workflow.ReplayAligner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * The in-memory paradox resolution bridge for one decision run of one workflow.
 *
 * It performs runtime stack-to-history diffing lazily, one activity call at a
 * time: the workflow code's command stream (which reflects the currently
 * deployed code graph) is matched against the recorded {@code ACTIVITY_SCHEDULED}
 * stream from {@code workflow_events}. When they align (the overwhelmingly
 * common case) translation is the identity and nothing is recorded — the
 * pristine path has zero structural overhead. When they diverge, a bounded
 * {@link AlignmentMapping} is produced (insertion / deletion / reorder) and
 * later committed to the durable ledger inside the same transaction as the
 * decision itself, so all future replays reuse the exact same bridge.
 *
 * Determinism: persisted mappings always short-circuit the diff, and the
 * running {@code delta} is restored from each mapping's {@code deltaAfter}, so
 * the translation function is a pure function of (history, ledger).
 *
 * Safety: fresh sequences are allocated strictly above every sequence ever seen
 * (history, ledger, or this run), so a healed schedule always gets a brand-new
 * idempotency key — the transactional outbox and cost records can never be
 * double-triggered by healing. Skipped records are merely orphaned, never
 * re-executed.
 */
public final class SequenceAlignmentSession implements ReplayAligner {

    /** Recorded activity command sequences -> activity type, from history. */
    private final NavigableMap<Long, String> historyActivityTypes;
    /** Every command sequence occupied in history (activities and side effects). */
    private final Set<Long> usedSeqs;
    /** The durable ledger loaded for this instance, keyed by code sequence. */
    private final Map<Long, AlignmentMapping> persisted;

    private final List<AlignmentMapping> newMappings = new ArrayList<>();
    private final Set<Long> consumed = new HashSet<>();
    private long delta = 0;
    private long freshFloor;

    public SequenceAlignmentSession(NavigableMap<Long, String> historyActivityTypes,
                                    Set<Long> usedCommandSeqs,
                                    List<AlignmentMapping> persistedMappings) {
        this.historyActivityTypes = new TreeMap<>(historyActivityTypes);
        this.usedSeqs = new HashSet<>(usedCommandSeqs);
        this.persisted = new HashMap<>();
        long floor = 0;
        for (long s : this.usedSeqs) {
            floor = Math.max(floor, s);
        }
        for (AlignmentMapping m : persistedMappings) {
            this.persisted.put(m.codeSeq(), m);
            floor = Math.max(floor, m.historySeq());
        }
        this.freshFloor = floor;
    }

    @Override
    public long alignActivity(long codeSeq, String activityType) {
        AlignmentMapping m = persisted.get(codeSeq);
        if (m != null) {
            // Ledger replay: reuse the exact virtualization decided earlier.
            delta = m.deltaAfter();
            consumed.add(m.historySeq());
            return m.historySeq();
        }
        long candidate = codeSeq + delta;
        String recorded = historyActivityTypes.get(candidate);

        if (recorded != null && recorded.equals(activityType) && consumed.add(candidate)) {
            return candidate; // aligned — the pristine path, nothing recorded
        }

        // Structural divergence paradox (or plain new work): try to locate an
        // unconsumed record of this activity anywhere in history first, so
        // already-recorded results are always reused instead of re-executed.
        Long found = findUnconsumed(activityType, candidate);
        if (found != null) {
            boolean forward = found > candidate;
            HealingResolutionType type = forward
                    ? HealingResolutionType.DELETION_SKIPPED
                    : HealingResolutionType.REORDER_ALIGNED;
            long deltaAfter = forward ? found - codeSeq : delta;
            record(new AlignmentMapping(codeSeq, found, type, deltaAfter, activityType));
            consumed.add(found);
            delta = deltaAfter;
            return found;
        }

        if (recorded == null && !usedSeqs.contains(candidate)
                && historyActivityTypes.tailMap(candidate, true).isEmpty()) {
            // Brand-new work appended past all recorded activities. Deterministic
            // without a mapping: delta is reproduced from the ledger on replay.
            usedSeqs.add(candidate);
            freshFloor = Math.max(freshFloor, candidate);
            return candidate;
        }

        // The activity exists nowhere in history: it was inserted by a deploy.
        // Virtualize a fresh slot so it executes with a brand-new idempotency key,
        // and shift subsequent commands back so the rest of history still lines up.
        long fresh = ++freshFloor;
        long deltaAfter = delta - 1;
        record(new AlignmentMapping(codeSeq, fresh, HealingResolutionType.INSERTION_MAPPED,
                deltaAfter, activityType));
        usedSeqs.add(fresh);
        consumed.add(fresh);
        delta = deltaAfter;
        return fresh;
    }

    @Override
    public long alignSideEffect(long codeSeq) {
        AlignmentMapping m = persisted.get(codeSeq);
        if (m != null) {
            delta = m.deltaAfter();
            return m.historySeq();
        }
        long candidate = codeSeq + delta;
        if (delta != 0 && historyActivityTypes.containsKey(candidate)) {
            // The shifted slot is occupied by a recorded activity — virtualize a
            // fresh slot for this side effect instead of colliding.
            long fresh = ++freshFloor;
            record(new AlignmentMapping(codeSeq, fresh, HealingResolutionType.INSERTION_MAPPED,
                    delta, "(side-effect)"));
            usedSeqs.add(fresh);
            return fresh;
        }
        return candidate;
    }

    /**
     * Prefer the first unconsumed record of the requested type at or after the
     * cursor (a deletion gap); fall back to an earlier unconsumed record (a
     * reorder). Deterministic: lowest matching sequence wins in each region.
     */
    private Long findUnconsumed(String activityType, long candidate) {
        for (var e : historyActivityTypes.tailMap(candidate, true).entrySet()) {
            if (!consumed.contains(e.getKey()) && e.getValue().equals(activityType)) {
                return e.getKey();
            }
        }
        for (var e : historyActivityTypes.headMap(candidate, false).entrySet()) {
            if (!consumed.contains(e.getKey()) && e.getValue().equals(activityType)) {
                return e.getKey();
            }
        }
        return null;
    }

    private void record(AlignmentMapping mapping) {
        newMappings.add(mapping);
        // Visible to later calls in this same run (and to determinism re-checks).
        persisted.put(mapping.codeSeq(), mapping);
    }

    /** Resolutions produced by this run, to be committed to the durable ledger. */
    public List<AlignmentMapping> newMappings() {
        return newMappings;
    }

    public boolean diverged() {
        return !newMappings.isEmpty();
    }
}
