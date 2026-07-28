package io.continuum.loops;

import io.continuum.semantic.TextVectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spots an agent going round in circles.
 *
 * <p>An agent that has lost the thread does not crash. It keeps working —
 * re-reading the same file, re-asking the same question, alternating between two
 * plans forever — and every step of it is a billable model call. The failure is
 * silent, unbounded and expensive, and the only thing that currently stops it is
 * somebody noticing the invoice.
 *
 * <p>Three shapes of loop, because they need different evidence:
 *
 * <ul>
 *   <li><b>Repetition.</b> The same step, over and over. Caught by exact match —
 *       cheap and certain.</li>
 *   <li><b>Paraphrase.</b> The same step in different words, which is what a
 *       model does when told to try again. Caught by meaning, not by string
 *       equality, since the whole point is that the string changed.</li>
 *   <li><b>Oscillation.</b> A → B → A → B. Neither step repeats consecutively,
 *       so a naive "same as last time" check never fires — this is the one that
 *       runs longest before anyone notices.</li>
 * </ul>
 *
 * <p><b>What it deliberately does not do.</b> Legitimate work repeats: a loop
 * over twenty files issues twenty similar steps and is not stuck. So repetition
 * alone never trips it — the signal is repetition <em>without progress</em>, and
 * progress is judged by whether anything new entered the conversation. An agent
 * that reads the same file twice but learns something in between is working.
 *
 * <p><b>Arguments are part of the step.</b> Measured, not assumed:
 * {@code "read file src/a.java"} and {@code "read file src/b.java"} score
 * <em>1.000</em> on the bag-of-words similarity this codebase uses, because the
 * vectoriser drops the filenames. Iterating over files is the most common
 * legitimate repetition there is, so a paraphrase check on prose alone would
 * fire on exactly the case it must not. A step whose arguments changed is a
 * different step, whatever the surrounding words do.
 *
 * <p><b>The honest limit on paraphrase detection.</b> Also measured:
 * {@code "I will retry the failed request"} and {@code "Let me try the request
 * again"} — plainly the same intent — score <em>0.258</em>, while
 * {@code "check the database connection"} and {@code "check the network
 * connection"} — plainly different — score <em>0.667</em>. The two populations
 * overlap, so no threshold separates them. The threshold here is therefore set
 * high: it catches near-identical rewording and misses the rest. High precision,
 * low recall, by choice — a false loop stops an agent that was working, and
 * that is the more expensive mistake.
 */
public final class LoopDetector {

    /**
     * Two steps are "the same" above this cosine similarity.
     *
     * <p>High on purpose. Real paraphrases can score as low as 0.26 and genuinely
     * different steps as high as 0.67 on this measure, so a lower threshold buys
     * recall by taking false positives — and a false loop stops an agent that
     * was working.
     */
    private static final double SAME = 0.90;
    /** How many identical steps before it is a loop rather than a retry. */
    private static final int REPEAT_LIMIT = 3;
    /** How many full A→B→A cycles before oscillation is called. */
    private static final int CYCLE_LIMIT = 2;
    /** Nothing is judged before this many steps. */
    private static final int MIN_STEPS = 3;

    private LoopDetector() {
    }

    public enum Kind { NONE, REPETITION, PARAPHRASE, OSCILLATION }

    /**
     * @param kind      what shape of loop, if any
     * @param at        the step index where the pattern became undeniable
     * @param evidence  the specific steps that repeated, for the developer
     */
    public record Verdict(Kind kind, int at, double confidence, String reason,
                          List<String> evidence) {

        public boolean looping() {
            return kind != Kind.NONE;
        }

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind.name());
            m.put("looping", looping());
            m.put("at", at);
            m.put("confidence", Math.round(confidence * 100) / 100.0);
            m.put("reason", reason);
            m.put("evidence", evidence);
            return m;
        }
    }

    private static final Verdict CLEAR =
            new Verdict(Kind.NONE, -1, 0, "no repeating pattern", List.of());

    /**
     * @param steps    what the agent has done, oldest first
     * @param progress whether each step produced something new — same length as
     *                 {@code steps}, or empty to assume nothing is known
     */
    public static Verdict inspect(List<String> steps, List<Boolean> progress) {
        if (steps == null || steps.size() < MIN_STEPS) {
            return CLEAR;
        }
        int n = steps.size();

        // Progress is the veto. Repetition with progress is a loop over work,
        // not a loop. Unknown progress is treated as none, because an agent that
        // cannot say whether it advanced is exactly the one worth watching.
        boolean anyProgress = false;
        if (progress != null && progress.size() == n) {
            for (int i = Math.max(0, n - REPEAT_LIMIT - 1); i < n; i++) {
                if (Boolean.TRUE.equals(progress.get(i))) {
                    anyProgress = true;
                    break;
                }
            }
        }
        if (anyProgress) {
            return CLEAR;
        }

        // --- exact repetition -------------------------------------------------
        String last = norm(steps.get(n - 1));
        int identical = 1;
        for (int i = n - 2; i >= 0 && norm(steps.get(i)).equals(last); i--) {
            identical++;
        }
        if (identical >= REPEAT_LIMIT) {
            return new Verdict(Kind.REPETITION, n - 1, 1.0,
                    identical + " identical steps in a row with nothing new learned",
                    List.of(steps.get(n - 1)));
        }

        // --- paraphrase -------------------------------------------------------
        // The same intent reworded, which is what a model produces when told to
        // try again. String equality cannot see it, which is the whole point.
        int similar = 1;
        double weakest = 1.0;
        for (int i = n - 2; i >= 0; i--) {
            double sim = TextVectors.cosine(steps.get(i), steps.get(n - 1));
            // Arguments veto similarity. Two steps that differ only in a
            // filename score 1.000 here, and iterating over files is the most
            // common legitimate repetition there is.
            if (sim < SAME || !sameArguments(steps.get(i), steps.get(n - 1))) {
                break;
            }
            weakest = Math.min(weakest, sim);
            similar++;
        }
        if (similar >= REPEAT_LIMIT) {
            return new Verdict(Kind.PARAPHRASE, n - 1, weakest,
                    similar + " steps saying the same thing in different words",
                    List.of(steps.get(n - similar), steps.get(n - 1)));
        }

        // --- oscillation ------------------------------------------------------
        // A→B→A→B. Neither step repeats consecutively, so the checks above never
        // fire — this is the shape that runs longest before anyone notices.
        if (n >= CYCLE_LIMIT * 2) {
            String a = steps.get(n - 1);
            String b = steps.get(n - 2);
            if (TextVectors.cosine(a, b) < SAME || !sameArguments(a, b)) {
                int cycles = 0;
                boolean intact = true;
                for (int i = n - 1; i >= 0 && intact; i -= 2) {
                    if (TextVectors.cosine(steps.get(i), a) < SAME
                            || !sameArguments(steps.get(i), a)) {
                        intact = false;
                        break;
                    }
                    if (i - 1 >= 0 && (TextVectors.cosine(steps.get(i - 1), b) < SAME
                            || !sameArguments(steps.get(i - 1), b))) {
                        intact = false;
                        break;
                    }
                    cycles++;
                }
                if (cycles >= CYCLE_LIMIT + 1) {
                    List<String> ev = new ArrayList<>();
                    ev.add(b);
                    ev.add(a);
                    return new Verdict(Kind.OSCILLATION, n - 1, 0.9,
                            "alternating between two steps for " + cycles + " cycles", ev);
                }
            }
        }

        return CLEAR;
    }

    /**
     * Whether two steps carry the same arguments.
     *
     * <p>Paths, identifiers, numbers, quoted strings — the parts a prose
     * vectoriser discards and a reader would call "which one". Two steps with
     * different arguments are different steps even when every surrounding word
     * matches.
     */
    static boolean sameArguments(String x, String y) {
        return argumentsOf(x).equals(argumentsOf(y));
    }

    private static java.util.Set<String> argumentsOf(String s) {
        if (s == null) {
            return java.util.Set.of();
        }
        java.util.Set<String> args = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = ARGUMENT.matcher(s);
        while (m.find()) {
            args.add(m.group().toLowerCase(java.util.Locale.ROOT));
        }
        return args;
    }

    /** Anything with a digit, a path or dot separator, or inside quotes. */
    private static final java.util.regex.Pattern ARGUMENT = java.util.regex.Pattern.compile(
            "\"[^\"]{1,80}\"|'[^']{1,80}'|[A-Za-z0-9_.\\-]*[/\\\\][A-Za-z0-9_./\\\\-]+"
                    + "|[A-Za-z0-9_-]*\\d[A-Za-z0-9_.-]*|[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z][A-Za-z0-9_.]*");

    private static String norm(String s) {
        return s == null ? "" : s.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }
}
