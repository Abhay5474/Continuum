package io.continuum.specialist;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Decides whether a pipeline step runs.
 *
 * <p>Pure, so the decision can be tested without a pipeline, a specialist or an
 * HTTP endpoint — and so the reason it gives can be asserted. Every decision
 * carries a sentence written for the person reading the trace, because a step
 * that silently did not run is indistinguishable from a step that ran and found
 * nothing, and those need different fixes.
 */
public final class StepRouter {

    private StepRouter() {
    }

    /**
     * @param run    whether to invoke the specialist
     * @param reason why, in words, always populated
     */
    public record Decision(boolean run, String reason) {
    }

    /**
     * @param findingsSoFar findings reported by earlier steps in this run
     * @param ranSoFar      how many earlier steps actually executed
     * @param prompt        the application user's own question, may be null
     * @param inputKind     what the pipeline was given
     */
    public static Decision decide(PipelineStep step, int findingsSoFar, int ranSoFar,
                                  String prompt, String inputKind) {
        PipelineStep.Condition when = step.when();

        if (when == PipelineStep.Condition.ALWAYS) {
            return new Decision(true, "Runs on every request.");
        }

        // A condition about "the previous step" with nothing before it cannot be
        // true or false, only meaningless. Configuration refuses this case, so
        // reaching it means a specialist was deleted out from under the
        // pipeline — run, rather than silently doing nothing.
        if (when.needsPredecessor() && ranSoFar == 0) {
            return new Decision(true,
                    "Nothing ran before this, so the condition could not be evaluated — "
                            + "running rather than skipping the only step left.");
        }

        return switch (when) {
            case IF_PREVIOUS_FOUND -> findingsSoFar > 0
                    ? new Decision(true, "An earlier specialist reported "
                            + findingsSoFar + (findingsSoFar == 1 ? " finding" : " findings")
                            + ", so this one drills in.")
                    : new Decision(false, "Nothing was found earlier, so there is nothing to "
                            + "drill into.");

            case IF_PREVIOUS_EMPTY -> findingsSoFar == 0
                    ? new Decision(true, "Nothing was found earlier, so this escalates to a "
                            + "second opinion.")
                    : new Decision(false, "An earlier specialist already found "
                            + findingsSoFar + (findingsSoFar == 1 ? " finding" : " findings")
                            + ", so escalating would only cost money.");

            case IF_PROMPT_MATCHES -> matches(step.pattern(), prompt)
                    ? new Decision(true, "The question matches /" + step.pattern() + "/.")
                    : new Decision(false, "The question does not match /" + step.pattern() + "/.");

            case IF_INPUT_IS -> {
                String want = step.pattern() == null ? "" : step.pattern().strip();
                boolean hit = want.equalsIgnoreCase(inputKind);
                yield hit
                        ? new Decision(true, "The input is " + want + ".")
                        : new Decision(false, "This one only handles " + want
                                + ", and the input is " + inputKind + ".");
            }

            default -> new Decision(true, "Runs on every request.");
        };
    }

    /**
     * A pattern that will not compile skips the step rather than failing the
     * request.
     *
     * <p>The alternative — running the specialist anyway — turns a typo in a
     * regular expression into an unexplained bill. Skipping is visible in the
     * trace with this reason attached, which is where someone will look.
     */
    private static boolean matches(String pattern, String prompt) {
        if (pattern == null || pattern.isBlank() || prompt == null) {
            return false;
        }
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
                    .matcher(prompt.toLowerCase(Locale.ROOT)).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /** Whether a pattern is usable, for validation at configuration time. */
    public static boolean validPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        try {
            Pattern.compile(pattern);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }
}
