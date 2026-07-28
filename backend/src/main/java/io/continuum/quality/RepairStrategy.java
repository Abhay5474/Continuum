package io.continuum.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What kind of defect this is, and what to actually ask for.
 *
 * <p>The gate already produces a list of defects, and the single-shot repair
 * concatenates them into one complaint: <i>"your previous answer had these
 * problems: …; fix them."</i> That works, and it wastes the most useful thing
 * the gate knows — <b>which kind of failure it is</b>. An answer that stopped
 * mid-sentence and an answer that invented a figure are not the same problem and
 * do not have the same fix, and a model asked to fix "these problems" tends to
 * rewrite everything, including the parts that were right.
 *
 * <p>So each defect is classified and gets an instruction naming the one thing
 * to change. Where nothing can be usefully asked — a refusal — the strategy says
 * so instead of paying for the same refusal twice.
 *
 * <p><b>Why this is safe to do at all.</b> Huang et al., <i>Large Language
 * Models Cannot Self-Correct Reasoning Yet</i> (ICLR 2024), found that unaided
 * self-correction usually makes answers worse: a model asked to reconsider will
 * find fault with correct work. Every strategy here is triggered by an
 * <em>external</em> signal — a failed format check, a missing sub-question, a
 * figure absent from the supplied context — never by the model's opinion of
 * itself. That is the distinction between repair that helps and repair that
 * drifts.
 */
public enum RepairStrategy {

    /** Nothing came back. */
    EMPTY("the answer was empty",
            "Your previous response was empty. Answer the original request."),

    /** Stopped mid-sentence — almost always a token limit. */
    TRUNCATION("the answer stopped mid-sentence",
            "Your previous answer was cut off before it finished. Produce the complete answer to "
                    + "the original request. Keep it within the length you were given."),

    /** The requested shape was not delivered. */
    FORMAT("the requested format was not used",
            "Your previous answer did not use the format the request asked for. Produce the same "
                    + "content again in exactly the requested format, changing nothing else."),

    /** The wrong number of items. */
    COUNT("the wrong number of items was returned",
            "Your previous answer returned the wrong number of items. Produce the answer again "
                    + "with exactly the number the request asked for — no more, no fewer."),

    /** Part of a multi-part question went unanswered. */
    INCOMPLETE("part of the question was not answered",
            "Your previous answer left part of the request unaddressed. Keep what you already "
                    + "wrote and add the missing part. Do not rewrite the parts that were "
                    + "already answered."),

    /** Figures that do not appear in the supplied material. */
    UNGROUNDED("figures do not appear in the supplied context",
            "Your previous answer contained figures that do not appear anywhere in the material "
                    + "you were given. Produce the answer again using only figures present in "
                    + "that material, or state that the figure is not available. Change nothing "
                    + "else."),

    /** Answered something else entirely. */
    IRRELEVANT("the answer does not address the question",
            "Your previous answer did not address the question that was asked. Answer that "
                    + "question directly."),

    /** A refusal. Not repairable, and asking again pays twice for the same no. */
    REFUSAL("the model refused the request", null),

    /** Recognised as a defect but not as a kind. Falls back to naming it. */
    UNCLASSIFIED("the answer did not meet the request", null);

    private final String label;
    private final String instruction;

    RepairStrategy(String label, String instruction) {
        this.label = label;
        this.instruction = instruction;
    }

    public String label() {
        return label;
    }

    /** Null when there is nothing worth asking for. */
    public String instruction() {
        return instruction;
    }

    public boolean repairable() {
        return instruction != null;
    }

    /**
     * Which strategy a defect calls for.
     *
     * <p>Matched on the phrases the gate and the deferral judge actually
     * produce. An unrecognised defect becomes {@link #UNCLASSIFIED} rather than
     * being dropped — a defect nobody classified is still a defect, and silently
     * ignoring it would make the repair engine quietly weaker than the
     * single-shot repair it replaces.
     */
    public static RepairStrategy classify(String defect) {
        if (defect == null || defect.isBlank()) {
            return UNCLASSIFIED;
        }
        String d = defect.toLowerCase(Locale.ROOT);
        if (d.contains("empty")) {
            return EMPTY;
        }
        if (d.contains("refus")) {
            return REFUSAL;
        }
        if (d.contains("cut off") || d.contains("mid-sentence") || d.contains("truncat")
                || d.contains("incomplete sentence") || d.contains("ends abruptly")) {
            return TRUNCATION;
        }
        if (d.contains("item") || d.contains("count") || d.contains("exactly")) {
            return COUNT;
        }
        if (d.contains("format") || d.contains("json") || d.contains("bullet")
                || d.contains("list") || d.contains("markdown") || d.contains("table")) {
            return FORMAT;
        }
        // Checked before UNGROUNDED. The gate's phrase for an off-topic answer
        // is "does not appear to address the question", which contains "not
        // appear" — so a substring match on that sent an irrelevant answer the
        // instruction for removing invented figures. Found by a failing test on
        // the gate's own wording.
        if (d.contains("address the question") || d.contains("relevan")) {
            return IRRELEVANT;
        }
        if (d.contains("ground") || d.contains("not appear in") || d.contains("not present")
                || d.contains("unsupported") || d.contains("figure")) {
            return UNGROUNDED;
        }
        if (d.contains("unaddressed") || d.contains("not answered") || d.contains("part of")
                || d.contains("sub-question") || d.contains("missing")) {
            return INCOMPLETE;
        }
        return UNCLASSIFIED;
    }

    /**
     * A plan for one repair attempt.
     *
     * @param strategies the distinct kinds of defect present, most severe first
     * @param instruction what to send, or null when nothing is worth asking
     */
    public record Plan(List<RepairStrategy> strategies, List<String> defects, String instruction) {

        public boolean repairable() {
            return instruction != null;
        }

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("strategies", strategies.stream().map(Enum::name).toList());
            m.put("defects", defects);
            m.put("repairable", repairable());
            return m;
        }
    }

    /**
     * Severity order. The first repairable kind supplies the instruction.
     *
     * <p>One instruction per attempt, deliberately. Asking a model to fix four
     * things at once is how the two that were already right get rewritten; the
     * loop comes back for the rest.
     */
    private static final List<RepairStrategy> SEVERITY = List.of(
            EMPTY, TRUNCATION, IRRELEVANT, COUNT, FORMAT, INCOMPLETE, UNGROUNDED, UNCLASSIFIED);

    public static Plan plan(List<String> defects) {
        if (defects == null || defects.isEmpty()) {
            return new Plan(List.of(), List.of(), null);
        }
        Set<RepairStrategy> kinds = new LinkedHashSet<>();
        for (String d : defects) {
            kinds.add(classify(d));
        }
        // A refusal anywhere makes the whole answer unrepairable: the model has
        // declined, and the other defects are downstream of that.
        if (kinds.contains(REFUSAL)) {
            return new Plan(List.of(REFUSAL), List.copyOf(defects), null);
        }

        List<RepairStrategy> ordered = new ArrayList<>();
        for (RepairStrategy s : SEVERITY) {
            if (kinds.contains(s)) {
                ordered.add(s);
            }
        }
        RepairStrategy lead = ordered.stream().filter(RepairStrategy::repairable).findFirst()
                .orElse(null);

        String instruction;
        if (lead == null) {
            // Only UNCLASSIFIED defects. Fall back to naming them, which is what
            // the single-shot repair always did — weaker than a targeted
            // instruction, still better than nothing.
            instruction = "Your previous answer had these specific problems: "
                    + String.join("; ", defects)
                    + ". Produce a corrected answer to the original request that fixes them. "
                    + "Return only the corrected answer.";
        } else {
            instruction = lead.instruction();
            if (ordered.size() > 1) {
                instruction += " (Other issues were also noted and will be handled separately; "
                        + "fix only what is asked here.)";
            }
        }
        return new Plan(ordered, List.copyOf(defects), instruction);
    }
}
