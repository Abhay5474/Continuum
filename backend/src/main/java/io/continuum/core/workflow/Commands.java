package io.continuum.core.workflow;

import java.util.List;

/**
 * The output of a single deterministic workflow decision run.
 *
 * A decision run replays the workflow from the start and produces a batch of
 * commands describing what the engine should durably do next. There are only a
 * handful of shapes, captured here.
 */
public final class Commands {

    private Commands() {
    }

    /** A non-deterministic value the workflow computed and must be persisted for replay. */
    public record RecordSideEffect(long commandSeq, String value) {
    }

    /** The workflow wants an activity executed. */
    public record ScheduleActivity(long commandSeq, String activityType, String input,
                                   int maxAttempts, int timeoutSeconds) {
    }

    /**
     * The full result of a decision run: any new side effects to persist, plus
     * exactly one terminal outcome (schedule / complete / fail / block).
     */
    public static final class Decision {
        public enum Kind { SCHEDULE, COMPLETE, FAIL, BLOCKED }

        private final Kind kind;
        private final List<RecordSideEffect> sideEffects;
        private final ScheduleActivity schedule;
        private final String result;
        private final String error;

        private Decision(Kind kind, List<RecordSideEffect> sideEffects,
                         ScheduleActivity schedule, String result, String error) {
            this.kind = kind;
            this.sideEffects = sideEffects;
            this.schedule = schedule;
            this.result = result;
            this.error = error;
        }

        public static Decision schedule(List<RecordSideEffect> se, ScheduleActivity s) {
            return new Decision(Kind.SCHEDULE, se, s, null, null);
        }

        public static Decision complete(List<RecordSideEffect> se, String result) {
            return new Decision(Kind.COMPLETE, se, null, result, null);
        }

        public static Decision fail(List<RecordSideEffect> se, String error) {
            return new Decision(Kind.FAIL, se, null, null, error);
        }

        public static Decision blocked(List<RecordSideEffect> se) {
            return new Decision(Kind.BLOCKED, se, null, null, null);
        }

        public Kind kind() {
            return kind;
        }

        public List<RecordSideEffect> sideEffects() {
            return sideEffects;
        }

        public ScheduleActivity schedule() {
            return schedule;
        }

        public String result() {
            return result;
        }

        public String error() {
            return error;
        }
    }
}
