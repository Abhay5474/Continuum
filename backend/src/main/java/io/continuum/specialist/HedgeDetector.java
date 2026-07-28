package io.continuum.specialist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Checks whether the model did what the confidence policy asked.
 *
 * <p>The policy can only ever ask. It appends an instruction and the model
 * complies or does not, and nothing in the request makes that binding. A policy
 * page that reports "hedged" because it *requested* a hedge is reporting its own
 * intent dressed up as an observation — which is worse than reporting nothing,
 * because it is believable.
 *
 * <p>So compliance is measured on the answer that came back.
 *
 * <p><b>This is lexical and it is stated as such.</b> It looks for the surface
 * marks of uncertainty and of declining to advise. It cannot tell a genuine
 * hedge from a decorative one — "this may possibly be an open wound; apply a
 * tourniquet immediately" contains every marker and hedges nothing. What it
 * reliably catches is the common failure: an instruction to hedge that produced
 * flat, unqualified prose. That failure is worth catching, and pretending to
 * more precision than a word list can deliver would repeat the mistake this
 * class exists to prevent.
 */
public final class HedgeDetector {

    private HedgeDetector() {
    }

    /** Marks of stated uncertainty. */
    private static final String[] HEDGES = {
            "may ", "might ", "could be", "possible", "possibly", "appears", "appear to",
            "seems", "suggests", "suggestive", "likely", "unlikely", "uncertain", "unclear",
            "not certain", "cannot be certain", "can't be certain", "not conclusive",
            "inconclusive", "moderate confidence", "low confidence", "hard to say",
            "difficult to say", "without being sure", "if it is", "if this is",
    };

    /** Marks of refusing to advise and asking for something better. */
    private static final String[] REFUSALS = {
            "cannot assess", "can't assess", "unable to assess", "not enough", "insufficient",
            "clearer", "closer", "better photo", "better image", "another photo", "another image",
            "could not be", "was not possible", "no analysis", "did not complete",
            "would need", "i'd need", "i would need", "please provide", "try again",
            "not good enough", "cannot determine", "can't determine", "unable to determine",
    };

    /** Marks of flat, unqualified assertion — the thing a hedge should displace. */
    private static final String[] ASSERTIONS = {
            "this is a", "the animal has", "it is a", "clearly", "definitely", "certainly",
            "without doubt", "obviously", "diagnosis is", "confirmed",
    };

    /**
     * @param complied     whether the answer bears the marks the action asked for
     * @param markers      the phrases actually found, so a developer can judge the call
     * @param method       always lexical; carried in the payload so the console can say so
     */
    public record Compliance(ConfidencePolicy.Action action, boolean checked, boolean complied,
                             List<String> markers, List<String> assertions, String method) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", action.name());
            m.put("checked", checked);
            m.put("complied", complied);
            m.put("markers", markers);
            m.put("flatAssertions", assertions);
            m.put("method", method);
            return m;
        }
    }

    public static Compliance check(ConfidencePolicy.Action action, String answer) {
        String text = answer == null ? "" : answer.toLowerCase(Locale.ROOT);

        // PASS asked for nothing, so there is nothing to have complied with.
        // Reporting "complied: true" here would inflate the success rate with
        // runs that were never tested.
        if (action == ConfidencePolicy.Action.PASS || action == ConfidencePolicy.Action.DECLINE) {
            return new Compliance(action, false, false, List.of(), List.of(), "not applicable");
        }

        List<String> found = new ArrayList<>();
        for (String h : action == ConfidencePolicy.Action.HEDGE ? HEDGES : REFUSALS) {
            if (text.contains(h)) {
                found.add(h.strip());
            }
        }
        // A refusal that also hedges is still a refusal; count both so a
        // developer sees the whole picture.
        if (action == ConfidencePolicy.Action.ASK_FOR_BETTER_INPUT) {
            for (String h : HEDGES) {
                if (text.contains(h) && !found.contains(h.strip())) {
                    found.add(h.strip());
                }
            }
        }

        List<String> flat = new ArrayList<>();
        for (String a : ASSERTIONS) {
            if (text.contains(a)) {
                flat.add(a);
            }
        }

        return new Compliance(action, true, !found.isEmpty(), found, flat, "lexical");
    }
}
