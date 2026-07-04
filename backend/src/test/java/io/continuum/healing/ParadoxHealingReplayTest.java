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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end contract of the Paradox Resolution Engine at the executor level:
 * a workflow instance started under an old code graph keeps making progress —
 * and completes — after the deployed code is structurally altered mid-flight.
 *
 * The harness simulates exactly what {@link io.continuum.core.engine.WorkflowEngine}
 * persists between decisions (schedules, completions, side effects, and the
 * healing ledger), so the exactly-once and determinism assertions here mirror
 * the real database behavior.
 */
class ParadoxHealingReplayTest {

    private final Json json = new Json(new ObjectMapper().registerModule(new JavaTimeModule()));
    private final WorkflowExecutor executor = new WorkflowExecutor(json);

    /** Simulated durable state: what the engine would hold in PostgreSQL. */
    private static final class SimulatedStore {
        final Map<Long, String> completed = new HashMap<>();
        final Map<Long, String> failed = new HashMap<>();
        final Map<Long, String> sideEffects = new HashMap<>();
        final Set<Long> scheduled = new HashSet<>();
        final TreeMap<Long, String> scheduledTypes = new TreeMap<>(); // ACTIVITY_SCHEDULED payloads
        final List<AlignmentMapping> ledger = new ArrayList<>();      // workflow_healing_logs
        final List<String> executedActivities = new ArrayList<>();    // simulated worker + cost records
        final Set<String> outboxKeys = new HashSet<>();               // idempotency keys ever enqueued
    }

    private SequenceAlignmentSession openSession(SimulatedStore db) {
        Set<Long> used = new HashSet<>(db.scheduledTypes.keySet());
        used.addAll(db.sideEffects.keySet());
        return new SequenceAlignmentSession(db.scheduledTypes, used, db.ledger);
    }

    /** Run decisions + simulated workers until the workflow completes or fails. */
    private Commands.Decision driveToCompletion(Workflow wf, SimulatedStore db, int maxTicks) {
        Commands.Decision last = null;
        for (int i = 0; i < maxTicks; i++) {
            SequenceAlignmentSession session = openSession(db);
            last = executor.runDecision(wf, "wf-1", "{}",
                    new HashMap<>(db.completed), new HashMap<>(db.failed),
                    new HashMap<>(db.sideEffects), new HashSet<>(db.scheduled), session);
            // Engine persists ledger + side effects atomically with the decision.
            db.ledger.addAll(session.newMappings());
            for (Commands.RecordSideEffect se : last.sideEffects()) {
                db.sideEffects.put(se.commandSeq(), se.value());
            }
            if (last.kind() == Commands.Decision.Kind.SCHEDULE) {
                Commands.ScheduleActivity s = last.schedule();
                String idempotencyKey = "wf-1:" + s.commandSeq();
                assertTrue(db.outboxKeys.add(idempotencyKey),
                        "healing must never re-enqueue an existing idempotency key: " + idempotencyKey);
                db.scheduledTypes.put(s.commandSeq(), s.activityType());
                db.scheduled.add(s.commandSeq());
                // Simulated worker completes it immediately (records cost once).
                db.executedActivities.add(s.activityType());
                db.completed.put(s.commandSeq(), json.write("result-" + s.activityType()));
                db.scheduled.remove(s.commandSeq());
            } else {
                return last;
            }
        }
        return last;
    }

    private static Workflow workflowOf(String type, String... activities) {
        return workflowOf(type, null, activities);
    }

    private static Workflow workflowOf(String type, AtomicInteger sideEffectRuns, String... activities) {
        return new Workflow() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Object execute(WorkflowContext ctx) {
                if (sideEffectRuns != null) {
                    ctx.sideEffect(() -> {
                        sideEffectRuns.incrementAndGet();
                        return "captured";
                    }, String.class);
                }
                Map<String, Object> out = new HashMap<>();
                for (String a : activities) {
                    out.put(a, ctx.executeActivity(a, Map.of(), String.class));
                }
                return out;
            }
        };
    }

    @Test
    void inFlightInstanceSurvivesAnInsertedActivityDeploy() {
        SimulatedStore db = new SimulatedStore();
        AtomicInteger sideEffectRuns = new AtomicInteger();

        // Old deploy: side effect + activities A, B. A and B both complete, but
        // the final decision has not run yet (e.g. the worker crashed first).
        Workflow oldCode = workflowOf("Demo", sideEffectRuns, "A", "B");
        for (int i = 0; i < 2; i++) {
            SequenceAlignmentSession s = openSession(db);
            Commands.Decision d = executor.runDecision(oldCode, "wf-1", "{}",
                    new HashMap<>(db.completed), new HashMap<>(db.failed),
                    new HashMap<>(db.sideEffects), new HashSet<>(db.scheduled), s);
            assertEquals(Commands.Decision.Kind.SCHEDULE, d.kind());
            assertFalse(s.diverged(), "pristine code must produce zero healing entries");
            for (Commands.RecordSideEffect se : d.sideEffects()) {
                db.sideEffects.put(se.commandSeq(), se.value());
            }
            Commands.ScheduleActivity sc = d.schedule();
            db.scheduledTypes.put(sc.commandSeq(), sc.activityType());
            db.outboxKeys.add("wf-1:" + sc.commandSeq());
            db.executedActivities.add(sc.activityType());
            db.completed.put(sc.commandSeq(), json.write("result-" + sc.activityType()));
        }
        assertEquals(List.of("A", "B"), db.executedActivities);

        // Deploy new code: X inserted between A and B. Old replay semantics
        // would hand X the recorded result of B — silent corruption. Healing
        // must virtualize a fresh slot for X and realign B to its recorded result.
        Workflow newCode = workflowOf("Demo", sideEffectRuns, "A", "X", "B");
        Commands.Decision fin = driveToCompletion(newCode, db, 10);

        assertEquals(Commands.Decision.Kind.COMPLETE, fin.kind());
        assertEquals(List.of("A", "B", "X"), db.executedActivities,
                "A and B ran once before the deploy; only X executed after healing");
        assertEquals(1, sideEffectRuns.get(), "side effect captured exactly once across the deploy");
        assertTrue(db.ledger.stream().anyMatch(
                        m -> m.resolutionType() == HealingResolutionType.INSERTION_MAPPED
                                && "X".equals(m.activityType())),
                "insertion resolution committed to the ledger");
        assertTrue(fin.result().contains("result-A"));
        assertTrue(fin.result().contains("result-X"));
        assertTrue(fin.result().contains("result-B"));

        // Determinism: replaying the healed instance yields the identical result
        // and produces no further healing entries.
        int ledgerSize = db.ledger.size();
        SequenceAlignmentSession replay = openSession(db);
        Commands.Decision again = executor.runDecision(newCode, "wf-1", "{}",
                new HashMap<>(db.completed), new HashMap<>(db.failed),
                new HashMap<>(db.sideEffects), new HashSet<>(db.scheduled), replay);
        assertEquals(Commands.Decision.Kind.COMPLETE, again.kind());
        assertEquals(fin.result(), again.result(), "healed replay is fully deterministic");
        assertFalse(replay.diverged());
        assertEquals(ledgerSize, db.ledger.size());
        assertEquals(1, sideEffectRuns.get(), "no double-counted side effects on healed replays");
    }

    @Test
    void inFlightInstanceSurvivesADeletedActivityDeploy() {
        SimulatedStore db = new SimulatedStore();

        // Old deploy A, B, C: all three complete, final decision not yet run.
        Workflow oldCode = workflowOf("Demo", "A", "B", "C");
        for (int i = 0; i < 3; i++) {
            SequenceAlignmentSession s = openSession(db);
            Commands.Decision d = executor.runDecision(oldCode, "wf-1", "{}",
                    new HashMap<>(db.completed), new HashMap<>(db.failed),
                    new HashMap<>(db.sideEffects), new HashSet<>(db.scheduled), s);
            assertFalse(s.diverged());
            Commands.ScheduleActivity sc = d.schedule();
            db.scheduledTypes.put(sc.commandSeq(), sc.activityType());
            db.outboxKeys.add("wf-1:" + sc.commandSeq());
            db.executedActivities.add(sc.activityType());
            db.completed.put(sc.commandSeq(), json.write("result-" + sc.activityType()));
        }
        assertEquals(List.of("A", "B", "C"), db.executedActivities);

        // Deploy removes B. The obsolete B record must be skipped — its recorded
        // result orphaned, never handed to C — and nothing may re-execute.
        Workflow newCode = workflowOf("Demo", "A", "C");
        Commands.Decision fin = driveToCompletion(newCode, db, 10);

        assertEquals(Commands.Decision.Kind.COMPLETE, fin.kind());
        assertEquals(List.of("A", "B", "C"), db.executedActivities,
                "no activity re-executed across the healed deploy");
        assertTrue(db.ledger.stream().anyMatch(
                m -> m.resolutionType() == HealingResolutionType.DELETION_SKIPPED));
        assertFalse(fin.result().contains("result-B"), "deleted step's result is orphaned");
    }

    @Test
    void unchangedWorkflowsPassThroughWithZeroHealingState() {
        SimulatedStore db = new SimulatedStore();
        Workflow code = workflowOf("Demo", new AtomicInteger(), "A", "B");
        Commands.Decision fin = driveToCompletion(code, db, 10);
        assertEquals(Commands.Decision.Kind.COMPLETE, fin.kind());
        assertTrue(db.ledger.isEmpty(), "no ledger rows for aligned code — zero structural overhead");
        assertEquals(List.of("A", "B"), db.executedActivities);
    }
}
