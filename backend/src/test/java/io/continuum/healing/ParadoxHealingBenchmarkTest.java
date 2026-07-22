package io.continuum.healing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.continuum.common.Json;
import io.continuum.core.workflow.Commands;
import io.continuum.core.workflow.Workflow;
import io.continuum.core.workflow.WorkflowContext;
import io.continuum.core.workflow.WorkflowExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The empirical measurement harness for the novel contribution — "Self-Healing
 * Deterministic Replay under Code Evolution" (the Paradox Resolution Engine).
 *
 * <p>Claim under test: an event-sourced workflow can survive an arbitrary
 * structural code change (activity insertion / deletion / reorder) applied while
 * it is mid-flight, with <b>zero crashes</b> and <b>zero double-executed side
 * effects</b> — eliminating the manual workflow-versioning that durable engines
 * such as Temporal require.
 *
 * <p>Method: over N deterministic, seeded trials, a workflow is started on an
 * "old code" activity graph and driven until its steps are recorded; a random
 * mutation is then applied to produce a "new code" graph deployed over the same
 * durable state; the workflow is driven to completion. Each trial checks the
 * invariants; the aggregate is asserted and printed as the paper's result table.
 */
class ParadoxHealingBenchmarkTest {

    private static final int TRIALS = 300;

    private final Json json = new Json(new ObjectMapper().registerModule(new JavaTimeModule()));
    private final WorkflowExecutor executor = new WorkflowExecutor(json);

    enum Mutation { INSERT, DELETE, REORDER, SWAP_TYPES }

    /** Simulated durable state — exactly what WorkflowEngine persists in PostgreSQL. */
    private static final class Store {
        final Map<Long, String> completed = new HashMap<>();
        final Map<Long, String> failed = new HashMap<>();
        final Map<Long, String> sideEffects = new HashMap<>();
        final Set<Long> scheduled = new HashSet<>();
        final TreeMap<Long, String> scheduledTypes = new TreeMap<>();
        final List<AlignmentMapping> ledger = new ArrayList<>();
        final Map<String, Integer> executionCounts = new HashMap<>(); // activityType -> times run
        final Set<String> idempotencyKeys = new HashSet<>();
        boolean duplicateKey = false;
    }

    /** A workflow whose graph is a fixed list of uniquely-typed activities + one side effect. */
    private static Workflow workflow(List<String> activities, AtomicInteger sideEffectRuns) {
        return new Workflow() {
            @Override
            public String type() {
                return "Mutable";
            }

            @Override
            public Object execute(WorkflowContext ctx) {
                ctx.sideEffect(() -> {
                    sideEffectRuns.incrementAndGet();
                    return "captured";
                }, String.class);
                Map<String, Object> out = new HashMap<>();
                for (String a : activities) {
                    out.put(a, ctx.executeActivity(a, Map.of(), String.class));
                }
                return out;
            }
        };
    }

    private SequenceAlignmentSession openSession(Store db) {
        Set<Long> used = new HashSet<>(db.scheduledTypes.keySet());
        used.addAll(db.sideEffects.keySet());
        return new SequenceAlignmentSession(db.scheduledTypes, used, db.ledger);
    }

    /** Drive decisions + a simulated worker pool until terminal. Records invariants. */
    private Commands.Decision drive(Workflow wf, Store db, int maxTicks) {
        Commands.Decision last = null;
        for (int i = 0; i < maxTicks; i++) {
            SequenceAlignmentSession session = openSession(db);
            last = executor.runDecision(wf, "wf-1", "{}",
                    new HashMap<>(db.completed), new HashMap<>(db.failed),
                    new HashMap<>(db.sideEffects), new HashSet<>(db.scheduled), session);
            db.ledger.addAll(session.newMappings());
            for (Commands.RecordSideEffect se : last.sideEffects()) {
                db.sideEffects.put(se.commandSeq(), se.value());
            }
            if (last.kind() != Commands.Decision.Kind.SCHEDULE) {
                return last;
            }
            for (Commands.ScheduleActivity s : last.schedules()) {
                String key = "wf-1:" + s.commandSeq();
                if (!db.idempotencyKeys.add(key)) {
                    db.duplicateKey = true; // exactly-once violation
                }
                db.scheduledTypes.put(s.commandSeq(), s.activityType());
                db.scheduled.add(s.commandSeq());
                db.executionCounts.merge(s.activityType(), 1, Integer::sum);
                db.completed.put(s.commandSeq(), json.write("result-" + s.activityType()));
                db.scheduled.remove(s.commandSeq());
            }
        }
        return last;
    }

    private List<String> mutate(List<String> base, Mutation m, Random rng, int freshCounter) {
        List<String> out = new ArrayList<>(base);
        switch (m) {
            case INSERT -> out.add(rng.nextInt(out.size() + 1), "NEW" + freshCounter);
            case DELETE -> {
                if (!out.isEmpty()) {
                    out.remove(rng.nextInt(out.size()));
                }
            }
            case REORDER -> {
                if (out.size() >= 2) {
                    int i = rng.nextInt(out.size() - 1);
                    java.util.Collections.swap(out, i, i + 1);
                }
            }
            case SWAP_TYPES -> {
                // Replace one activity with a fresh type (delete+insert at same spot).
                if (!out.isEmpty()) {
                    out.set(rng.nextInt(out.size()), "NEW" + freshCounter);
                }
            }
        }
        return out;
    }

    @Test
    void nMutationsZeroCrashesZeroDoubleSideEffects() {
        Random rng = new Random(20260101L); // deterministic, reproducible
        int crashes = 0;
        int doubleSideEffects = 0;
        int doubleExecutions = 0;
        int actuallyDiverged = 0;
        Map<HealingResolutionType, Integer> resolutionCounts = new EnumMap<>(HealingResolutionType.class);
        for (HealingResolutionType t : HealingResolutionType.values()) {
            resolutionCounts.put(t, 0);
        }

        for (int trial = 0; trial < TRIALS; trial++) {
            Store db = new Store();
            AtomicInteger sideEffectRuns = new AtomicInteger();

            // "Old code": 3–6 uniquely-typed activities, driven to full record.
            int n = 3 + rng.nextInt(4);
            List<String> oldCode = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                oldCode.add(String.valueOf((char) ('A' + k)));
            }
            Commands.Decision oldFinal = drive(workflow(oldCode, sideEffectRuns), db, 40);
            assertEquals(Commands.Decision.Kind.COMPLETE, oldFinal.kind(), "baseline must complete");

            // "Deploy" a random structural mutation and run the new code over the same state.
            Mutation mutation = Mutation.values()[rng.nextInt(Mutation.values().length)];
            List<String> newCode = mutate(oldCode, mutation, rng, trial);
            Commands.Decision newFinal = drive(workflow(newCode, sideEffectRuns), db, 60);

            // ---- invariants ----
            if (newFinal.kind() != Commands.Decision.Kind.COMPLETE) {
                crashes++;
            }
            if (sideEffectRuns.get() != 1) {
                doubleSideEffects++; // the captured side effect must run exactly once, ever
            }
            if (db.executionCounts.values().stream().anyMatch(c -> c > 1)) {
                doubleExecutions++;  // no activity may be executed twice
            }
            if (db.duplicateKey) {
                doubleSideEffects++; // a reused idempotency key is a duplicate-delivery risk
            }
            if (!db.ledger.isEmpty()) {
                actuallyDiverged++;
                for (AlignmentMapping mp : db.ledger) {
                    resolutionCounts.merge(mp.resolutionType(), 1, Integer::sum);
                }
            }
        }

        // ---- the paper's result table ----
        System.out.println("=== Paradox Resolution Engine — self-healing under code evolution ===");
        System.out.println("trials (code mutations deployed mid-flight): " + TRIALS);
        System.out.println("crashes:                                     " + crashes);
        System.out.println("double-executed side effects:                " + doubleSideEffects);
        System.out.println("double-executed activities:                  " + doubleExecutions);
        System.out.println("trials that structurally diverged & healed:  " + actuallyDiverged);
        System.out.println("resolutions by type:                         " + resolutionCounts);

        assertEquals(0, crashes, "every mutated workflow must complete — zero crashes");
        assertEquals(0, doubleSideEffects, "side effects must fire exactly once across any deploy");
        assertEquals(0, doubleExecutions, "no activity may be re-executed after healing");
        assertTrue(actuallyDiverged > TRIALS / 5,
                "the benchmark must actually exercise real divergences, got " + actuallyDiverged);
    }
}
