package io.continuum.saga;

import io.continuum.declarative.WorkflowSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two things a rollback must get right: the order, and the truth about what
 * it could not undo.
 */
class SagaPlanTest {

    private static WorkflowSpec spec(String... idsWithCompensation) {
        WorkflowSpec s = new WorkflowSpec();
        List<WorkflowSpec.Step> steps = new java.util.ArrayList<>();
        for (String raw : idsWithCompensation) {
            boolean undoable = raw.endsWith("*");
            String id = undoable ? raw.substring(0, raw.length() - 1) : raw;
            WorkflowSpec.Step step = new WorkflowSpec.Step();
            step.setId(id);
            WorkflowSpec.Call call = new WorkflowSpec.Call();
            call.setUrl("https://example.test/" + id);
            step.setCall(call);
            if (undoable) {
                WorkflowSpec.Call undo = new WorkflowSpec.Call();
                undo.setUrl("https://example.test/undo/" + id);
                step.setCompensate(undo);
            }
            steps.add(step);
        }
        s.setSteps(steps);
        return s;
    }

    private static List<String> ids(SagaPlan.Plan plan) {
        return plan.compensations().stream().map(SagaPlan.Compensation::stepId).toList();
    }

    @Test
    @DisplayName("Compensations run in reverse order of completion")
    void reverseOrder() {
        // Reserve, then charge. Refunding must precede releasing the reservation
        // or the stock is free for someone else in the window between the two.
        var plan = SagaPlan.forFailure(spec("reserve*", "charge*", "ship*"),
                List.of("reserve", "charge"), "ship");

        assertThat(ids(plan)).containsExactly("charge", "reserve");
        assertThat(plan.complete()).isTrue();
    }

    @Test
    @DisplayName("The failed step is not compensated — it never completed")
    void failedStepExcluded() {
        var plan = SagaPlan.forFailure(spec("reserve*", "charge*"),
                List.of("reserve", "charge"), "charge");

        assertThat(ids(plan)).containsExactly("reserve");
    }

    @Test
    @DisplayName("A step with no compensation is named, not quietly dropped")
    void gapsAreNamed() {
        // The email is already in someone's inbox. Reporting a clean rollback
        // here is worse than reporting the truth.
        var plan = SagaPlan.forFailure(spec("charge*", "email", "ship*"),
                List.of("charge", "email"), "ship");

        assertThat(ids(plan)).containsExactly("charge");
        assertThat(plan.uncompensated()).containsExactly("email");
        assertThat(plan.complete()).isFalse();
        assertThat(plan.summary()).contains("email").contains("effects remain");
    }

    @Test
    @DisplayName("A compensation with no URL counts as no compensation")
    void blankUrlIsNotACompensation() {
        WorkflowSpec s = spec("charge*");
        s.getSteps().get(0).getCompensate().setUrl("  ");

        var plan = SagaPlan.forFailure(s, List.of("charge"), "ship");

        assertThat(plan.compensations()).isEmpty();
        assertThat(plan.uncompensated()).containsExactly("charge");
    }

    @Test
    @DisplayName("A step missing from the spec is a gap, not a silent skip")
    void stepRemovedFromDefinition() {
        // The definition was edited under a running workflow. Whatever that step
        // did is still out there and nothing here can undo it.
        var plan = SagaPlan.forFailure(spec("charge*"), List.of("charge", "vanished"), "ship");

        assertThat(ids(plan)).containsExactly("charge");
        assertThat(plan.uncompensated()).containsExactly("vanished");
    }

    @Test
    @DisplayName("Nothing completed means nothing to undo, and it says so")
    void nothingCompleted() {
        var plan = SagaPlan.forFailure(spec("reserve*"), List.of(), "reserve");

        assertThat(plan.compensations()).isEmpty();
        assertThat(plan.uncompensated()).isEmpty();
        assertThat(plan.complete()).isTrue();
        assertThat(plan.summary()).contains("nothing to undo");
    }

    @Test
    @DisplayName("A null spec produces gaps rather than an exception")
    void nullSpec() {
        // A workflow whose definition cannot be read still needs its rollback
        // report; throwing here would lose the list of what is outstanding.
        var plan = SagaPlan.forFailure(null, List.of("charge"), "ship");

        assertThat(plan.uncompensated()).containsExactly("charge");
        assertThat(plan.complete()).isFalse();
    }
}
