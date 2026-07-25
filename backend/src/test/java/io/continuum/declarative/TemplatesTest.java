package io.continuum.declarative;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Step-to-step references must resolve from recorded values only. */
class TemplatesTest {

    private final Map<String, Object> scope = Map.of(
            "input", Map.of("sku", "A-1", "qty", 3),
            "steps", Map.of(
                    "reserve", Map.of("holdId", "H-9", "lines", List.of(Map.of("id", "L1"))),
                    "charge", Map.of("ok", true)));

    @Test
    void resolvesAWholeReferenceWithItsTypeIntact() {
        assertThat(Templates.resolveString("${input.qty}", scope)).isEqualTo(3);
        assertThat(Templates.resolveString("${steps.charge.ok}", scope)).isEqualTo(true);
    }

    @Test
    void interpolatesMixedText() {
        assertThat(Templates.resolveString("order-${input.sku}-${steps.reserve.holdId}", scope))
                .isEqualTo("order-A-1-H-9");
    }

    @Test
    void indexesIntoArrays() {
        assertThat(Templates.resolveString("${steps.reserve.lines[0].id}", scope)).isEqualTo("L1");
    }

    @Test
    void missingPathsResolveToNullRatherThanThrowing() {
        assertThat(Templates.resolveString("${steps.nope.field}", scope)).isNull();
        assertThat(Templates.resolveString("x-${steps.nope.field}", scope)).isEqualTo("x-");
    }

    @Test
    void resolvesNestedStructures() {
        Object out = Templates.resolve(
                Map.of("sku", "${input.sku}", "nested", List.of("${steps.reserve.holdId}")), scope);
        assertThat(out).isEqualTo(Map.of("sku", "A-1", "nested", List.of("H-9")));
    }

    @Test
    void leavesPlainTextAlone() {
        assertThat(Templates.resolveString("no refs here", scope)).isEqualTo("no refs here");
    }
}
