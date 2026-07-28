package io.continuum.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ordering is the easy part. The two behaviours worth testing are the ones that
 * make a scheduler safe: it refuses work it cannot deliver, and it does not let
 * low-priority work wait forever.
 */
class DeadlineSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private static DeadlineScheduler.Task task(String id, DeadlineScheduler.Priority p,
                                               long waitedSeconds, Long deadlineSeconds,
                                               long estimateSeconds) {
        return new DeadlineScheduler.Task(id, p, NOW.minusSeconds(waitedSeconds),
                deadlineSeconds == null ? null : NOW.plusSeconds(deadlineSeconds),
                Duration.ofSeconds(estimateSeconds));
    }

    private static List<String> runnableOrder(List<DeadlineScheduler.Decision> d) {
        return d.stream().filter(DeadlineScheduler.Decision::runnable)
                .map(DeadlineScheduler.Decision::id).toList();
    }

    @Test
    @DisplayName("Priority beats arrival order")
    void priorityBeatsFifo() {
        // The whole point: the batch job arrived first and still goes second.
        var out = DeadlineScheduler.order(List.of(
                task("batch", DeadlineScheduler.Priority.BATCH, 10, null, 5),
                task("interactive", DeadlineScheduler.Priority.INTERACTIVE, 0, null, 5)), NOW);

        assertThat(runnableOrder(out)).containsExactly("interactive", "batch");
    }

    @Test
    @DisplayName("Within a band, the earliest deadline goes first")
    void earliestDeadlineFirst() {
        var out = DeadlineScheduler.order(List.of(
                task("loose", DeadlineScheduler.Priority.NORMAL, 0, 600L, 5),
                task("tight", DeadlineScheduler.Priority.NORMAL, 0, 30L, 5)), NOW);

        assertThat(runnableOrder(out)).containsExactly("tight", "loose");
    }

    @Test
    @DisplayName("No deadline sorts last, not first")
    void noDeadlineIsNotUrgent() {
        // An unset deadline means "whenever". Treating it as zero would make
        // every unannotated request the most urgent thing in the queue.
        var out = DeadlineScheduler.order(List.of(
                task("unset", DeadlineScheduler.Priority.NORMAL, 0, null, 5),
                task("dated", DeadlineScheduler.Priority.NORMAL, 0, 900L, 5)), NOW);

        assertThat(runnableOrder(out)).containsExactly("dated", "unset");
    }

    @Test
    @DisplayName("A task that cannot make its deadline is refused, not started")
    void impossibleDeadlineIsRefused() {
        // Starting it spends a worker on a result nobody can use and delays the
        // tasks that could still make theirs.
        var out = DeadlineScheduler.order(List.of(
                task("doomed", DeadlineScheduler.Priority.INTERACTIVE, 0, 5L, 60)), NOW);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).runnable()).isFalse();
        assertThat(out.get(0).reason()).contains("cannot meet its deadline");
    }

    @Test
    @DisplayName("Refusing one task does not disturb the ones that can still run")
    void refusalDoesNotBlockOthers() {
        var out = DeadlineScheduler.order(List.of(
                task("doomed", DeadlineScheduler.Priority.NORMAL, 0, 1L, 60),
                task("fine", DeadlineScheduler.Priority.NORMAL, 0, 600L, 5)), NOW);

        assertThat(runnableOrder(out)).containsExactly("fine");
        assertThat(out).anyMatch(d -> d.id().equals("doomed") && !d.runnable());
    }

    @Test
    @DisplayName("Waiting long enough lifts a task above newer, higher-priority work")
    void agingPreventsStarvation() {
        // Without this, "low priority" quietly means "never" under sustained load.
        long waited = DeadlineScheduler.AGING_STEP.toSeconds() * 2;
        var out = DeadlineScheduler.order(List.of(
                task("fresh-interactive", DeadlineScheduler.Priority.INTERACTIVE, 0, null, 5),
                task("old-batch", DeadlineScheduler.Priority.BATCH, waited, null, 5)), NOW);

        // Two bands of boost lift BATCH to INTERACTIVE; the tie then goes to the
        // one that has been waiting longer.
        assertThat(runnableOrder(out)).containsExactly("old-batch", "fresh-interactive");
        assertThat(out.get(0).reason()).contains("for waiting");
    }

    @Test
    @DisplayName("Aging is capped, so waiting cannot outrank everything forever")
    void agingIsBounded() {
        // A day-old batch job is still a batch job. An uncapped boost would let
        // a stale backlog take over the queue the moment load eased.
        long waited = DeadlineScheduler.AGING_STEP.toSeconds() * 500;
        var old = task("ancient-batch", DeadlineScheduler.Priority.BATCH, waited, null, 5);

        assertThat(DeadlineScheduler.effectiveBand(old, NOW))
                .isEqualTo(DeadlineScheduler.Priority.INTERACTIVE.band());
    }

    @Test
    @DisplayName("An unrecognised priority reads as NORMAL, never as BATCH")
    void unknownPriorityIsNotDemoted() {
        // A typo in a client must not silently send that caller's work to the
        // back of every queue.
        assertThat(DeadlineScheduler.Priority.of("intractive"))
                .isEqualTo(DeadlineScheduler.Priority.NORMAL);
        assertThat(DeadlineScheduler.Priority.of(null))
                .isEqualTo(DeadlineScheduler.Priority.NORMAL);
        assertThat(DeadlineScheduler.Priority.of(" interactive "))
                .isEqualTo(DeadlineScheduler.Priority.INTERACTIVE);
    }

    @Test
    @DisplayName("An empty queue produces no decisions")
    void emptyQueue() {
        assertThat(DeadlineScheduler.order(List.of(), NOW)).isEmpty();
        assertThat(DeadlineScheduler.order(null, NOW)).isEmpty();
    }
}
