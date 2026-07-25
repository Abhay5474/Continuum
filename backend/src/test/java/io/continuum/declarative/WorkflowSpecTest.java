package io.continuum.declarative;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A definition is authored by a customer and then executed durably, so a bad
 * spec must be rejected at publish time rather than discovered halfway through a
 * production run — and layering must be a pure function of the spec, or replay
 * would not be deterministic.
 */
class WorkflowSpecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private WorkflowSpec spec(Object json) {
        return mapper.convertValue(json, WorkflowSpec.class);
    }

    private Map<String, Object> step(String id, List<String> deps) {
        return Map.of("id", id, "dependsOn", deps,
                "call", Map.of("url", "https://api.example.com/" + id));
    }

    @Test
    void acceptsAValidGraph() {
        WorkflowSpec s = spec(Map.of("steps", List.of(
                step("a", List.of()), step("b", List.of("a")))));
        assertThatCode(s::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnEmptyWorkflow() {
        assertThatThrownBy(() -> spec(Map.of("steps", List.of())).validate())
                .isInstanceOf(WorkflowSpec.InvalidSpecException.class)
                .hasMessageContaining("at least one step");
    }

    @Test
    void rejectsADependencyCycle() {
        assertThatThrownBy(() -> spec(Map.of("steps", List.of(
                step("a", List.of("b")), step("b", List.of("a"))))).validate())
                .isInstanceOf(WorkflowSpec.InvalidSpecException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void rejectsAnUnknownDependency() {
        assertThatThrownBy(() -> spec(Map.of("steps", List.of(step("a", List.of("ghost"))))).validate())
                .isInstanceOf(WorkflowSpec.InvalidSpecException.class)
                .hasMessageContaining("unknown step");
    }

    @Test
    void rejectsDuplicateStepIds() {
        assertThatThrownBy(() -> spec(Map.of("steps", List.of(
                step("a", List.of()), step("a", List.of())))).validate())
                .isInstanceOf(WorkflowSpec.InvalidSpecException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void rejectsAStepWithoutATarget() {
        assertThatThrownBy(() -> spec(Map.of("steps", List.of(Map.of("id", "a")))).validate())
                .isInstanceOf(WorkflowSpec.InvalidSpecException.class)
                .hasMessageContaining("call.url");
    }

    @Test
    void independentStepsShareALayerAndDependentsFollow() {
        WorkflowSpec s = spec(Map.of("steps", List.of(
                step("charge", List.of()),
                step("reserve", List.of()),
                step("email", List.of("charge", "reserve")))));
        List<List<WorkflowSpec.Step>> layers = s.topologicalLayers();

        assertThat(layers).hasSize(2);
        assertThat(layers.get(0)).extracting(WorkflowSpec.Step::getId)
                .containsExactly("charge", "reserve"); // sorted, so replay is stable
        assertThat(layers.get(1)).extracting(WorkflowSpec.Step::getId).containsExactly("email");
    }

    @Test
    void layeringIsStableAcrossRuns() {
        // Replay depends on the same spec producing the same schedule every time.
        WorkflowSpec s = spec(Map.of("steps", List.of(
                step("z", List.of()), step("m", List.of()), step("a", List.of()))));
        assertThat(s.topologicalLayers().get(0)).extracting(WorkflowSpec.Step::getId)
                .isEqualTo(s.topologicalLayers().get(0).stream().map(WorkflowSpec.Step::getId).toList())
                .containsExactly("a", "m", "z");
    }
}
