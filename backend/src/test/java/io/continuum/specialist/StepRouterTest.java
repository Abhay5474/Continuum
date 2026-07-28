package io.continuum.specialist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routing decides what does not run, which makes it the easiest thing in the
 * pipeline to get quietly wrong: a step that never fires and a step that fires
 * and finds nothing produce the same answer. So every decision carries a reason,
 * and these tests assert the reasons as well as the outcomes.
 */
class StepRouterTest {

    private static PipelineStep step(PipelineStep.Condition when, String pattern) {
        return new PipelineStep(1L, when, pattern);
    }

    private static StepRouter.Decision decide(PipelineStep s, int findings, int ran) {
        return StepRouter.decide(s, findings, ran, "is my dog bleeding?", "image");
    }

    @Test
    @DisplayName("ALWAYS runs, which is what every pre-routing pipeline did")
    void alwaysRuns() {
        StepRouter.Decision d = decide(step(PipelineStep.Condition.ALWAYS, null), 0, 0);

        assertThat(d.run()).isTrue();
        assertThat(d.reason()).isNotBlank();
    }

    @Test
    @DisplayName("A null condition is ALWAYS, not a crash")
    void nullConditionDefaults() {
        assertThat(new PipelineStep(1L, null, null).when())
                .isEqualTo(PipelineStep.Condition.ALWAYS);
    }

    // --- the cascade, both directions ----------------------------------------

    @Test
    @DisplayName("Drill-down runs only when something was found")
    void drillDown() {
        PipelineStep s = step(PipelineStep.Condition.IF_PREVIOUS_FOUND, null);

        assertThat(decide(s, 2, 1).run()).isTrue();
        assertThat(decide(s, 0, 1).run()).isFalse();
        assertThat(decide(s, 0, 1).reason()).contains("nothing to drill into");
    }

    @Test
    @DisplayName("Escalation runs only when nothing was found")
    void escalate() {
        PipelineStep s = step(PipelineStep.Condition.IF_PREVIOUS_EMPTY, null);

        assertThat(decide(s, 0, 1).run()).isTrue();
        assertThat(decide(s, 0, 1).reason()).contains("second opinion");
        assertThat(decide(s, 3, 1).run()).isFalse();
        // The reason names the cost, because that is the point of the condition.
        assertThat(decide(s, 3, 1).reason()).contains("only cost money");
    }

    @Test
    @DisplayName("Singular and plural read correctly in the reason")
    void reasonsAreReadable() {
        PipelineStep s = step(PipelineStep.Condition.IF_PREVIOUS_FOUND, null);

        assertThat(decide(s, 1, 1).reason()).contains("1 finding").doesNotContain("1 findings");
        assertThat(decide(s, 2, 1).reason()).contains("2 findings");
    }

    @Test
    @DisplayName("A condition about the previous step with nothing before it runs anyway")
    void noPredecessorRunsRatherThanVanishes() {
        // Configuration refuses this, so reaching it means a specialist was
        // deleted out from under the pipeline. Skipping would leave a pipeline
        // that quietly does nothing at all.
        for (PipelineStep.Condition c : new PipelineStep.Condition[]{
                PipelineStep.Condition.IF_PREVIOUS_FOUND, PipelineStep.Condition.IF_PREVIOUS_EMPTY}) {
            StepRouter.Decision d = decide(step(c, null), 0, 0);
            assertThat(d.run()).as("%s", c).isTrue();
            assertThat(d.reason()).contains("could not be evaluated");
        }
    }

    // --- prompt matching -------------------------------------------------------

    @Test
    @DisplayName("Prompt matching is case-insensitive and finds substrings")
    void promptMatching() {
        PipelineStep s = step(PipelineStep.Condition.IF_PROMPT_MATCHES, "bleed|wound|hurt");

        assertThat(decide(s, 0, 1).run()).isTrue();
        assertThat(StepRouter.decide(s, 0, 1, "What breed is she?", "image").run()).isFalse();
        assertThat(StepRouter.decide(s, 0, 1, "BLEEDING badly", "image").run()).isTrue();
    }

    @Test
    @DisplayName("A pattern that will not compile skips rather than failing the request")
    void brokenPatternSkips() {
        // Running anyway would turn a typo in a regular expression into an
        // unexplained bill. Skipping shows up in the trace, which is where
        // someone will actually look.
        PipelineStep s = step(PipelineStep.Condition.IF_PROMPT_MATCHES, "[unclosed");

        assertThat(decide(s, 0, 1).run()).isFalse();
        assertThat(StepRouter.validPattern("[unclosed")).isFalse();
        assertThat(StepRouter.validPattern("bleed|wound")).isTrue();
    }

    @Test
    @DisplayName("No prompt never matches")
    void missingPromptDoesNotMatch() {
        PipelineStep s = step(PipelineStep.Condition.IF_PROMPT_MATCHES, "bleed");

        assertThat(StepRouter.decide(s, 0, 1, null, "image").run()).isFalse();
    }

    // --- input kind ------------------------------------------------------------

    @Test
    @DisplayName("Input matching gates a specialist to one kind")
    void inputKindGating() {
        PipelineStep s = step(PipelineStep.Condition.IF_INPUT_IS, "audio");

        assertThat(StepRouter.decide(s, 0, 1, "hi", "audio").run()).isTrue();
        StepRouter.Decision miss = StepRouter.decide(s, 0, 1, "hi", "image");
        assertThat(miss.run()).isFalse();
        assertThat(miss.reason()).contains("only handles audio").contains("input is image");
    }

    @Test
    @DisplayName("Input matching ignores case and stray spaces")
    void inputKindIsForgiving() {
        assertThat(StepRouter.decide(step(PipelineStep.Condition.IF_INPUT_IS, "  Audio "), 0, 1,
                "hi", "audio").run()).isTrue();
    }

    @Test
    @DisplayName("Every decision explains itself")
    void everyDecisionHasAReason() {
        for (PipelineStep.Condition c : PipelineStep.Condition.values()) {
            for (int findings : new int[]{0, 2}) {
                StepRouter.Decision d = decide(step(c, "bleed"), findings, 1);
                assertThat(d.reason()).as("%s with %d findings", c, findings).isNotBlank();
            }
        }
    }
}
