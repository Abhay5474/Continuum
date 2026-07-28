package io.continuum.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which queued task to run next when there are more than there are workers.
 *
 * <p>The queue is FIFO. That is fair and it is the wrong kind of fair: a
 * ten-minute batch summarisation submitted at 09:00 runs before an interactive
 * request submitted at 09:01, and the person waiting on the second one has no
 * idea why. Worse, a task with a deadline it can no longer meet still occupies a
 * worker while it misses it.
 *
 * <p>Ordering is <b>earliest-deadline-first among the highest priority band</b>.
 * EDF is optimal for meeting deadlines on a single resource (Liu &amp; Layland,
 * 1973); priority bands sit above it because not all deadlines are equally worth
 * meeting, and a strict EDF queue lets a batch job with a tight deadline
 * outrank an interactive request with a loose one.
 *
 * <p>Two behaviours matter more than the ordering.
 *
 * <p><b>A task that cannot meet its deadline is not started.</b> Running it
 * consumes a worker to produce a result nobody can use, and it delays the tasks
 * that could still make theirs. It is failed immediately with a reason instead.
 *
 * <p><b>Starvation is bounded.</b> A pure priority queue lets background work
 * wait forever under sustained interactive load. Waiting time is aged into the
 * effective priority, so anything queued long enough eventually outranks new
 * arrivals. Without that, "low priority" quietly means "never".
 */
public final class DeadlineScheduler {

    /** After this long queued, a task gains a priority band. */
    public static final Duration AGING_STEP = Duration.ofMinutes(2);
    /** No amount of waiting lifts a task more than this many bands. */
    private static final int MAX_AGE_BOOST = 2;

    private DeadlineScheduler() {
    }

    public enum Priority {
        /** Bulk work nobody is waiting for. */
        BATCH(0),
        /** The default. */
        NORMAL(1),
        /** Someone is watching a cursor blink. */
        INTERACTIVE(2);

        private final int band;

        Priority(int band) {
            this.band = band;
        }

        public int band() {
            return band;
        }

        public static Priority of(String raw) {
            if (raw == null || raw.isBlank()) {
                return NORMAL;
            }
            try {
                return valueOf(raw.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // An unrecognised value must never read as BATCH: a typo would
                // silently send that caller's work to the back of every queue.
                return NORMAL;
            }
        }
    }

    /**
     * @param id        the task
     * @param priority  its band
     * @param queuedAt  when it was submitted
     * @param deadline  when its result stops being useful, or null
     * @param estimated how long it is expected to take
     */
    public record Task(String id, Priority priority, Instant queuedAt, Instant deadline,
                       Duration estimated) {
    }

    /** @param reason why this task was ordered where it was, or dropped */
    public record Decision(String id, int rank, boolean runnable, String reason) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("rank", rank);
            m.put("runnable", runnable);
            m.put("reason", reason);
            return m;
        }
    }

    /**
     * Orders the queue and marks anything that can no longer make its deadline.
     *
     * @param now the clock, passed in so this stays pure and testable
     */
    public static List<Decision> order(List<Task> queue, Instant now) {
        if (queue == null || queue.isEmpty()) {
            return List.of();
        }
        List<Task> runnable = new ArrayList<>();
        List<Decision> out = new ArrayList<>();

        for (Task t : queue) {
            if (t.deadline() != null) {
                Duration need = t.estimated() == null ? Duration.ZERO : t.estimated();
                Instant finishBy = now.plus(need);
                if (finishBy.isAfter(t.deadline())) {
                    // Starting it spends a worker on a result nobody can use and
                    // delays the tasks that could still make theirs.
                    out.add(new Decision(t.id(), -1, false, String.format(
                            "cannot meet its deadline — needs %ds and only %ds remain",
                            need.toSeconds(),
                            Math.max(0, Duration.between(now, t.deadline()).toSeconds()))));
                    continue;
                }
            }
            runnable.add(t);
        }

        runnable.sort(Comparator
                .comparingInt((Task t) -> -effectiveBand(t, now))
                .thenComparing(t -> t.deadline() == null ? Instant.MAX : t.deadline())
                .thenComparing(Task::queuedAt));

        for (int i = 0; i < runnable.size(); i++) {
            Task t = runnable.get(i);
            int boost = ageBoost(t, now);
            String why = t.deadline() != null
                    ? t.priority() + ", due in " + Math.max(0,
                            Duration.between(now, t.deadline()).toSeconds()) + "s"
                    : t.priority() + ", no deadline";
            if (boost > 0) {
                why += " (+" + boost + " band" + (boost == 1 ? "" : "s")
                        + " for waiting " + Duration.between(t.queuedAt(), now).toMinutes() + "m)";
            }
            out.add(new Decision(t.id(), i, true, why));
        }
        return out;
    }

    /** The band a task is treated as, once waiting is taken into account. */
    static int effectiveBand(Task t, Instant now) {
        return Math.min(Priority.INTERACTIVE.band(), t.priority().band() + ageBoost(t, now));
    }

    private static int ageBoost(Task t, Instant now) {
        if (t.queuedAt() == null || AGING_STEP.isZero()) {
            return 0;
        }
        long waited = Duration.between(t.queuedAt(), now).toSeconds();
        if (waited <= 0) {
            return 0;
        }
        return (int) Math.min(MAX_AGE_BOOST, waited / AGING_STEP.toSeconds());
    }
}
