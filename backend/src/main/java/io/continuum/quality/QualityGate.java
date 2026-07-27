package io.continuum.quality;

import io.continuum.cascade.DeferralJudge;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import io.continuum.provider.model.Role;
import io.continuum.semantic.TextVectors;
import io.continuum.uncertainty.AnswerClusterer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a finished answer against the request that asked for it.
 *
 * <p>Everything else on the response path asks "did the call succeed?". This
 * asks "did the answer do what was asked?" — a different question, and the one
 * that decides whether a developer parsing the output gets a product or a demo.
 *
 * <p><b>Scope is deliberately narrow.</b> LLM-as-judge is well documented as
 * biased (position, verbosity, self-preference — Zheng et al., NeurIPS 2023), and
 * Huang et al. (ICLR 2024) found unaided self-correction often makes reasoning
 * <em>worse</em>. So this gate does not attempt to judge whether an answer is
 * good. It checks four things that are objectively checkable from the request
 * and the text, and nothing else:
 *
 * <ul>
 *   <li><b>Adherence</b> — the explicit contract: JSON when JSON was asked for,
 *       N items when N were demanded, not truncated, not a refusal.</li>
 *   <li><b>Completeness</b> — a request with three questions in it should not
 *       come back having answered one.</li>
 *   <li><b>Grounding</b> — when the request supplied context, figures asserted in
 *       the answer should appear in that context. A number that exists nowhere
 *       in the source material is the most checkable form of fabrication.</li>
 *   <li><b>Relevance</b> — the answer is about the question at all.</li>
 * </ul>
 *
 * <p>Every failure names itself and points at a specific defect, because the
 * repair step needs something concrete to tell the model. "Your answer was poor"
 * is not actionable; "you returned prose when the request required JSON" is.
 */
@Component
public class QualityGate {

    /** A refusal is different from hedging: the model declined rather than failed. */
    private static final Pattern REFUSAL = Pattern.compile(
            "\\b(i (can't|cannot|won't|will not) (help|assist|provide|do that|comply)"
                    + "|i'm (not able|unable) to (help|assist|provide)"
                    + "|(that|this) (request|content) (violates|is against)"
                    + "|i must (decline|refuse))\\b",
            Pattern.CASE_INSENSITIVE);

    /** Sentence-ish split, for counting the parts of a multi-part request. */
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.?!])\\s+");

    /** What the gate decided should happen. */
    public enum Action {
        /** Nothing wrong that this gate can see. */
        PASS,
        /** A specific, nameable defect the model can be asked to fix. */
        REPAIR,
        /** Wrong in a way regeneration will not fix. */
        BLOCK
    }

    /** One checked dimension. */
    public record Dimension(String name, double score, String defect) {
        public boolean failed() {
            return defect != null;
        }
    }

    /** The gate's verdict. */
    public record Verdict(double score, Action action, List<Dimension> dimensions, List<String> defects) {

        public boolean passed() {
            return action == Action.PASS;
        }

        public String summary() {
            return defects.isEmpty() ? "meets the request" : String.join("; ", defects);
        }

        /**
         * The instruction handed to the repair attempt. Names the defects and
         * nothing else — a vague complaint produces a vague correction.
         */
        public String repairInstruction() {
            return "Your previous answer had these specific problems: "
                    + String.join("; ", defects)
                    + ". Produce a corrected answer to the original request that fixes them. "
                    + "Return only the corrected answer.";
        }
    }

    private final DeferralJudge judge;
    private final AnswerClusterer clusterer;

    public QualityGate(DeferralJudge judge, AnswerClusterer clusterer) {
        this.judge = judge;
        this.clusterer = clusterer;
    }

    /**
     * Checks an answer.
     *
     * @param threshold below which the verdict becomes an action rather than a note
     */
    public Verdict check(LlmRequest request, String answer, double complexity, double threshold) {
        List<Dimension> dims = new ArrayList<>();
        List<String> defects = new ArrayList<>();

        if (answer == null || answer.isBlank()) {
            dims.add(new Dimension("adherence", 0, "the answer is empty"));
            defects.add("the answer is empty");
            return new Verdict(0, Action.REPAIR, dims, defects);
        }

        String question = lastUser(request);
        String context = supportingContext(request);
        String trimmed = answer.strip();

        // --- adherence: the explicit contract -------------------------------
        // Reuses the cascade's judge rather than re-deriving format and
        // truncation rules. One definition of "broke the contract", used by both.
        DeferralJudge.Verdict jv = judge.judge(request, trimmed, complexity);
        boolean refused = REFUSAL.matcher(trimmed).find();
        double adherence = refused ? 0 : jv.rawScore();
        String adherenceDefect = null;
        if (refused) {
            adherenceDefect = "the model refused the request";
        } else if (!jv.concerns().isEmpty()) {
            adherenceDefect = String.join("; ", jv.concerns());
        }
        dims.add(new Dimension("adherence", adherence, adherenceDefect));

        // --- completeness: every part answered ------------------------------
        Completeness comp = completeness(question, trimmed);
        dims.add(new Dimension("completeness", comp.score(), comp.defect()));

        // --- grounding: figures traceable to the supplied context ------------
        Grounding gr = grounding(context, trimmed);
        dims.add(new Dimension("grounding", gr.score(), gr.defect()));

        // --- relevance: about the question at all ----------------------------
        double relevance = question.isBlank() ? 1.0 : TextVectors.cosine(question, trimmed);
        String relevanceDefect = relevance < 0.05
                ? "the answer does not appear to address the question" : null;
        dims.add(new Dimension("relevance", relevance < 0.05 ? 0 : 1, relevanceDefect));

        for (Dimension d : dims) {
            if (d.failed()) {
                defects.add(d.defect());
            }
        }

        // Weighted toward adherence, which is the dimension whose failures are
        // unambiguous. Grounding and completeness are heuristics and are
        // weighted so they cannot fail an answer on their own.
        double score = 0.45 * adherence + 0.2 * comp.score() + 0.2 * gr.score()
                + 0.15 * (relevance < 0.05 ? 0 : 1);

        Action action;
        if (refused) {
            // A refusal is a decision, not a defect. Asking again is how you get
            // the same refusal twice and pay for both.
            action = Action.BLOCK;
        } else if (score >= threshold) {
            action = Action.PASS;
        } else {
            action = Action.REPAIR;
        }
        return new Verdict(score, action, dims, defects);
    }

    private record Completeness(double score, String defect) {
    }

    /**
     * Whether a multi-part request got a multi-part answer.
     *
     * <p>Only fires on requests that are visibly multi-part — two or more
     * question sentences. Guessing at implicit sub-questions would produce
     * noise, and a gate that cries wolf gets turned off.
     */
    private Completeness completeness(String question, String answer) {
        if (question == null || question.isBlank()) {
            return new Completeness(1, null);
        }
        List<String> parts = new ArrayList<>();
        for (String s : SENTENCE.split(question)) {
            String t = s.strip();
            if (t.endsWith("?") && t.length() > 8) {
                parts.add(t);
            }
        }
        if (parts.size() < 2) {
            return new Completeness(1, null);
        }
        int covered = 0;
        for (String part : parts) {
            // Each sub-question should have some lexical footprint in the answer.
            if (TextVectors.cosine(part, answer) >= 0.12) {
                covered++;
            }
        }
        double score = (double) covered / parts.size();
        return covered == parts.size()
                ? new Completeness(1, null)
                : new Completeness(score,
                        String.format("the request asked %d questions and the answer addresses %d",
                                parts.size(), covered));
    }

    private record Grounding(double score, String defect) {
    }

    /**
     * Whether the figures in the answer appear in the supplied context.
     *
     * <p>Deliberately one-directional and forgiving. It only runs when the
     * request carried substantial context, only looks at numeric claims, and
     * tolerates a minority of unsupported ones — a model may legitimately
     * compute a total that appears nowhere in its source. What it catches is the
     * answer that is mostly figures none of which came from anywhere.
     */
    private Grounding grounding(String context, String answer) {
        if (context == null || context.strip().length() < 120) {
            return new Grounding(1, null);
        }
        Set<String> answerClaims = numeric(clusterer.claims(answer));
        if (answerClaims.size() < 2) {
            return new Grounding(1, null);
        }
        Set<String> contextClaims = numeric(clusterer.claims(context));
        int supported = 0;
        for (String c : answerClaims) {
            if (contextClaims.contains(c)) {
                supported++;
            }
        }
        double score = (double) supported / answerClaims.size();
        if (score >= 0.5) {
            return new Grounding(1, null);
        }
        return new Grounding(score, String.format(
                "%d of %d figures in the answer do not appear in the supplied context",
                answerClaims.size() - supported, answerClaims.size()));
    }

    /** Claims that are figures rather than names — the checkable subset. */
    private static Set<String> numeric(Set<String> claims) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : claims) {
            if (!c.isEmpty() && Character.isDigit(c.charAt(0))) {
                out.add(c);
            }
        }
        return out;
    }

    private static String lastUser(LlmRequest request) {
        if (request == null || request.messages() == null) {
            return "";
        }
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            Message m = request.messages().get(i);
            if (m.role() == Role.USER && m.content() != null) {
                return m.content();
            }
        }
        return "";
    }

    /** Everything except the final user turn: the material the answer should rest on. */
    private static String supportingContext(LlmRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<Message> msgs = request.messages();
        int lastUserIdx = -1;
        for (int i = msgs.size() - 1; i >= 0 && lastUserIdx < 0; i--) {
            if (msgs.get(i).role() == Role.USER) {
                lastUserIdx = i;
            }
        }
        for (int i = 0; i < msgs.size(); i++) {
            if (i == lastUserIdx) {
                continue;
            }
            if (msgs.get(i).content() != null) {
                sb.append(' ').append(msgs.get(i).content());
            }
        }
        return sb.toString();
    }

    /** Per-dimension scores, for the console. */
    public static Map<String, Double> scores(Verdict v) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (Dimension d : v.dimensions()) {
            m.put(d.name(), d.score());
        }
        return m;
    }
}
