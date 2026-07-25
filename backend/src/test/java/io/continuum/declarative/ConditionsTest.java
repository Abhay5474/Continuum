package io.continuum.declarative;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A guard is re-evaluated on every replay, so it must be a pure function of
 * recorded values — and it must never become a way to run author-supplied code.
 */
class ConditionsTest {

    private final Map<String, Object> scope = Map.of(
            "input", Map.of("amount", 4999, "tier", "gold"),
            "steps", Map.of(
                    "risk", Map.of("score", 0.91, "flagged", true),
                    "charge", Map.of("status", 200)));

    @Test
    void comparesNumbers() {
        assertThat(Conditions.evaluate("${steps.risk.score} > 0.8", scope)).isTrue();
        assertThat(Conditions.evaluate("${steps.risk.score} < 0.5", scope)).isFalse();
        assertThat(Conditions.evaluate("${steps.charge.status} == 200", scope)).isTrue();
    }

    @Test
    void comparesStringsWithOrWithoutQuotes() {
        assertThat(Conditions.evaluate("${input.tier} == gold", scope)).isTrue();
        assertThat(Conditions.evaluate("${input.tier} == 'gold'", scope)).isTrue();
        assertThat(Conditions.evaluate("${input.tier} != silver", scope)).isTrue();
    }

    @Test
    void comparesBooleans() {
        assertThat(Conditions.evaluate("${steps.risk.flagged} == true", scope)).isTrue();
    }

    @Test
    void joinsWithAnd() {
        assertThat(Conditions.evaluate("${steps.risk.score} > 0.8 and ${input.tier} == gold", scope)).isTrue();
        assertThat(Conditions.evaluate("${steps.risk.score} > 0.8 and ${input.tier} == silver", scope)).isFalse();
    }

    @Test
    void joinsWithOr() {
        assertThat(Conditions.evaluate("${input.tier} == silver or ${steps.risk.score} > 0.8", scope)).isTrue();
        assertThat(Conditions.evaluate("${input.tier} == silver or ${steps.risk.score} > 0.99", scope)).isFalse();
    }

    @Test
    void aMissingValueDoesNotThrowMidRun() {
        // Guards run inside a durable execution; an unknown path must not blow up
        // the workflow, it simply does not match.
        assertThat(Conditions.evaluate("${steps.nope.field} == 1", scope)).isFalse();
    }

    @Test
    void rejectsUnparseableGuardsAtPublishTime() {
        assertThatThrownBy(() -> Conditions.parse("totally not a condition"))
                .isInstanceOf(WorkflowSpec.InvalidSpecException.class);
        assertThatThrownBy(() -> Conditions.parse("${a} == 1 and ${b} == 2 or ${c} == 3"))
                .isInstanceOf(WorkflowSpec.InvalidSpecException.class)
                .hasMessageContaining("Mixing");
    }

    @Test
    void neverExecutesAuthorSuppliedCode() {
        // The grammar is comparisons only, so something that looks like a call is
        // just an opaque literal — it is never invoked. Comparing a non-number
        // with an ordering operator is false rather than an arbitrary answer.
        assertThat(Conditions.evaluate("now() > 5", scope)).isFalse();
        assertThat(Conditions.evaluate("now() == now()", scope)).isTrue(); // same literal
    }

    @Test
    void isDeterministicForTheSameScope() {
        // Replay depends on a guard answering identically every time it is run.
        for (int i = 0; i < 50; i++) {
            assertThat(Conditions.evaluate("${steps.risk.score} > 0.8 and ${input.tier} == gold", scope))
                    .isTrue();
        }
    }
}
