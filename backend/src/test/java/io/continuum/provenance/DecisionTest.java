package io.continuum.provenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The export shape is the feature. A provenance record only readable inside
 * Continuum has solved the easy half of the problem, so what matters is that it
 * lands in a collector looking like every other span.
 */
class DecisionTest {

    @Test
    @DisplayName("A decision carries what was chosen, why, and what was not chosen")
    void decisionIsSelfDescribing() {
        Decision d = new Decision(Decision.Stage.ROUTE, "mock-small",
                "low complexity", List.of("mock-large", "gpt-4o"), 0.0002, 12);

        Map<String, Object> m = d.describe();
        assertThat(m).containsEntry("stage", "ROUTE")
                .containsEntry("choice", "mock-small")
                .containsEntry("reason", "low complexity");
        assertThat(m.get("alternatives")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactly("mock-large", "gpt-4o");
    }

    @Test
    @DisplayName("Model decisions use the GenAI convention attribute")
    void usesGenAiConventions() {
        // gen_ai.request.model is what an existing dashboard already groups by;
        // inventing continuum.model instead would make this invisible to it.
        var span = Decision.of(Decision.Stage.PROVIDER, "mock-small", "first choice").asSpan();

        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) span.get("attributes");
        assertThat(attrs).containsEntry("gen_ai.request.model", "mock-small");
        assertThat(span).containsEntry("name", "continuum.provider");
    }

    @Test
    @DisplayName("Cost is reported under the usage convention, and only when there is any")
    void costIsConventional() {
        @SuppressWarnings("unchecked")
        Map<String, Object> withCost = (Map<String, Object>) new Decision(
                Decision.Stage.CASCADE, "escalated", "cheap answer deferred",
                List.of(), 0.004, 90).asSpan().get("attributes");
        assertThat(withCost).containsEntry("gen_ai.usage.cost", 0.004);

        @SuppressWarnings("unchecked")
        Map<String, Object> free = (Map<String, Object>) Decision
                .of(Decision.Stage.CACHE, "hit", "identical question").asSpan().get("attributes");
        // A zero-cost attribute on every span is noise that hides the real ones.
        assertThat(free).doesNotContainKey("gen_ai.usage.cost");
    }

    @Test
    @DisplayName("Every stage produces a span, whatever it decided")
    void everyStageExports() {
        for (Decision.Stage stage : Decision.Stage.values()) {
            var span = Decision.of(stage, "x", "y").asSpan();
            assertThat(span.get("name")).as("%s", stage)
                    .isEqualTo("continuum." + stage.name().toLowerCase());
            assertThat(span).containsKeys("durationMs", "attributes");
        }
    }

    @Test
    @DisplayName("Empty alternatives are omitted rather than exported as an empty list")
    void emptyAlternativesAreOmitted() {
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) Decision
                .of(Decision.Stage.FIREWALL, "clean", "nothing matched").asSpan().get("attributes");

        assertThat(attrs).doesNotContainKey("continuum.alternatives");
        assertThat(attrs).containsEntry("continuum.stage", "firewall");
    }
}
