package io.continuum.declarative;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Waits and completion callbacks must be validated where the author can see it. */
class WaitAndCallbackSpecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private WorkflowSpec spec(Object json) {
        return mapper.convertValue(json, WorkflowSpec.class);
    }

    @Test
    void acceptsAWaitStep() {
        WorkflowSpec s = spec(Map.of("steps", List.of(
                Map.of("id", "cool-off", "type", "WAIT", "waitSeconds", 30))));
        assertThatCode(s::validate).doesNotThrowAnyException();
        assertThat(s.getSteps().get(0).getType()).isEqualTo(WorkflowSpec.Kind.WAIT);
    }

    @Test
    void aWaitDoesNotNeedAUrl() {
        assertThatCode(() -> spec(Map.of("steps", List.of(
                Map.of("id", "w", "type", "WAIT", "waitSeconds", 5)))).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnOutOfRangeWait() {
        assertThatThrownBy(() -> spec(Map.of("steps", List.of(
                Map.of("id", "w", "type", "WAIT", "waitSeconds", 999_999)))).validate())
                .isInstanceOf(WorkflowSpec.InvalidSpecException.class)
                .hasMessageContaining("waitSeconds");
    }

    @Test
    void rejectsAWaitWithoutADuration() {
        assertThatThrownBy(() -> spec(Map.of("steps", List.of(
                Map.of("id", "w", "type", "WAIT")))).validate())
                .isInstanceOf(WorkflowSpec.InvalidSpecException.class);
    }

    @Test
    void rejectsABadGuardAtPublishTime() {
        assertThatThrownBy(() -> spec(Map.of("steps", List.of(
                Map.of("id", "a", "condition", "nonsense",
                        "call", Map.of("url", "https://x/1"))))).validate())
                .isInstanceOf(WorkflowSpec.InvalidSpecException.class);
    }

    @Test
    void rejectsACallbackWithoutAUrl() {
        assertThatThrownBy(() -> spec(Map.of(
                "steps", List.of(Map.of("id", "a", "call", Map.of("url", "https://x/1"))),
                "onComplete", Map.of("method", "POST"))).validate())
                .isInstanceOf(WorkflowSpec.InvalidSpecException.class)
                .hasMessageContaining("onComplete");
    }

    @Test
    void acceptsACallback() {
        assertThatCode(() -> spec(Map.of(
                "steps", List.of(Map.of("id", "a", "call", Map.of("url", "https://x/1"))),
                "onComplete", Map.of("url", "https://x/done"))).validate())
                .doesNotThrowAnyException();
    }
}
