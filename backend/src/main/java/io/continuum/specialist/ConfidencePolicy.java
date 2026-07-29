package io.continuum.specialist;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides what the model is allowed to do with the evidence it has been given.
 *
 * <p>This is what stops a 0.31 detection becoming confident first-aid advice.
 * Without it, the strength of the evidence reaches the model as a number in a
 * sentence and is then, in practice, ignored: a model handed "fracture (31%
 * confident)" will write a paragraph about the fracture. Telling it the number
 * is necessary and not sufficient. It also has to be told what to do.
 *
 * <p>Five bands, because two of them are not about strength at all:
 *
 * <table>
 *   <tr><td>{@code STRONG}</td><td>Pass through. The evidence supports a direct answer.</td></tr>
 *   <tr><td>{@code MEDIUM}</td><td>Answer, but say out loud how sure you are.</td></tr>
 *   <tr><td>{@code WEAK}</td><td>Do not advise on the finding. Ask for something better.</td></tr>
 *   <tr><td>{@code NONE}</td><td>Looked, found nothing. That is a real result.</td></tr>
 *   <tr><td>{@code UNAVAILABLE}</td><td>Never looked. Not a result at all.</td></tr>
 * </table>
 *
 * <p>{@code NONE} and {@code UNAVAILABLE} are separate on purpose and it is the
 * distinction most worth having. A clean photo and a dead endpoint both produce
 * zero findings; only the first is evidence of anything. Collapsing them is how a
 * system reports "nothing wrong" during an outage.
 *
 * <p><b>What this cannot do.</b> The policy shapes the instruction, not the
 * outcome. A model told to hedge may not hedge — the instruction is a request,
 * and nothing here can make it binding. That is why the hedge is
 * <em>measured</em> afterwards rather than assumed: see
 * {@link HedgeDetector}. An unverified instruction is a comfortable fiction, and
 * a policy page reporting "hedged: yes" because it asked is worse than no page.
 */
public final class ConfidencePolicy {

    public enum Band {
        STRONG, MEDIUM, WEAK, NONE, UNAVAILABLE,
        /**
         * Evidence was produced and none of it carries a confidence — recovered
         * text, extracted fields, transformed records.
         *
         * <p>Not a strength. A sixth band exists because grading unscored
         * evidence on a scale it was never measured against gives the wrong
         * answer in both directions: called STRONG it invents certainty, called
         * WEAK it makes the model refuse to read a document it was handed.
         */
        UNSCORED
    }

    /** What the band asks the model to do. */
    public enum Action {
        /** Answer normally. */
        PASS,
        /** Answer, but state the uncertainty explicitly. */
        HEDGE,
        /** Do not advise; ask for input good enough to advise on. */
        ASK_FOR_BETTER_INPUT,
        /** Do not call the model at all. */
        DECLINE
    }

    /** Defaults. Taken from the context builder rather than restated, so the
     *  prose bands and the policy bands cannot drift apart — a prompt reading
     *  "Observed:" above an instruction to hedge would contradict itself. */
    public static final double DEFAULT_STRONG = ContextBuilder.STRONG;
    public static final double DEFAULT_WEAK = ContextBuilder.POSSIBLE;

    private ConfidencePolicy() {
    }

    /**
     * The decision.
     *
     * @param instruction appended to the model's prompt; empty when {@code PASS}
     * @param declined    true when no model call should be made at all
     */
    public record Decision(Band band, Action action, String instruction, double evidence,
                           boolean declined, String reason) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("band", band.name());
            m.put("action", action.name());
            m.put("evidence", evidence);
            m.put("declined", declined);
            m.put("reason", reason);
            return m;
        }
    }

    /**
     * @param strongAt      at or above this, evidence is strong
     * @param weakAt        below this, evidence is too weak to advise on
     * @param declineOnNoEvidence when nothing was found or nothing ran, refuse
     *                            outright rather than asking the model to say so
     */
    public static Decision decide(ContextBuilder.Context ctx, double strongAt, double weakAt,
                                  boolean declineOnNoEvidence) {
        if (!ctx.analysisRan()) {
            return new Decision(Band.UNAVAILABLE,
                    declineOnNoEvidence ? Action.DECLINE : Action.ASK_FOR_BETTER_INPUT,
                    declineOnNoEvidence ? "" : UNAVAILABLE_INSTRUCTION,
                    0, declineOnNoEvidence,
                    "No specialist answered, so nothing was examined.");
        }
        if (!ctx.anythingFound()) {
            return new Decision(Band.NONE,
                    declineOnNoEvidence ? Action.DECLINE : Action.ASK_FOR_BETTER_INPUT,
                    declineOnNoEvidence ? "" : NONE_INSTRUCTION,
                    0, declineOnNoEvidence,
                    "The analysis ran and found nothing above the reporting threshold.");
        }

        if (ctx.unscoredOnly()) {
            return new Decision(Band.UNSCORED, Action.PASS, UNSCORED_INSTRUCTION, 0, false,
                    "Evidence was recovered but none of it carries a confidence figure, so no "
                            + "threshold applies to it.");
        }

        double e = ctx.topConfidence();
        if (e >= strongAt) {
            return new Decision(Band.STRONG, Action.PASS, "", e, false,
                    "Strongest finding at " + pct(e) + ", at or above the strong threshold.");
        }
        if (e >= weakAt) {
            return new Decision(Band.MEDIUM, Action.HEDGE, HEDGE_INSTRUCTION, e, false,
                    "Strongest finding at " + pct(e) + ", between the thresholds.");
        }
        return new Decision(Band.WEAK, Action.ASK_FOR_BETTER_INPUT, WEAK_INSTRUCTION, e, false,
                "Strongest finding at " + pct(e) + ", below the weak threshold.");
    }

    /** The canned answer used when the policy declines without calling a model. */
    public static String declineMessage(Band band) {
        return band == Band.UNAVAILABLE
                ? "This could not be assessed — the automated analysis did not complete, so "
                        + "nothing was examined. Please try again shortly."
                : "Nothing could be identified from what was provided with enough confidence to "
                        + "advise on. A clearer or closer input would help.";
    }

    // The instructions are written as constraints on the answer rather than as
    // descriptions of the evidence. "Be careful" is not actionable; "do not name
    // a specific condition" is.

    private static final String HEDGE_INSTRUCTION = """

            The evidence above is moderate, not strong. Say so plainly in your answer \
            rather than leaving it implied: name what is uncertain, and say what would \
            make the picture clearer. Do not state anything as established fact that \
            rests only on the moderate findings above.""";

    private static final String UNSCORED_INSTRUCTION = """
            The evidence you have been given carries no confidence score. It is content \
            recovered from the input — text, fields or records — not a probabilistic \
            detection. Do not state or imply a percentage of your own, and do not describe \
            the evidence as confirmed or uncertain; say what it contains, and say plainly \
            when it does not contain what the question asks about.
            """;

    private static final String WEAK_INSTRUCTION = """

            The evidence above is weak — too weak to advise on. Do not name a specific \
            condition, diagnosis or cause, and do not give instructions that would only \
            make sense if the weak findings were correct. Instead, say clearly that the \
            input was not good enough to assess, and describe what would be needed. \
            General safety advice that holds regardless of the findings is fine.""";

    private static final String NONE_INSTRUCTION = """

            Nothing was identified. Do not speculate about what might be present. Say \
            that the analysis found nothing, and describe what a more useful input would \
            look like. If the question can be answered usefully without any findings, \
            answer that part only.""";

    private static final String UNAVAILABLE_INSTRUCTION = """

            No analysis was performed — the check did not complete. This is not the same \
            as finding nothing, and you must not present it as an all-clear. Say that the \
            assessment could not be carried out, and do not describe or diagnose anything.""";

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }
}
