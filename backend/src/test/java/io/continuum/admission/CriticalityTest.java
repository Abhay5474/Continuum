package io.continuum.admission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CriticalityTest {

    @Test
    @DisplayName("An unrecognised value reads as NORMAL, never as BACKGROUND")
    void unknownIsNormal() {
        // The dangerous default. Reading a typo as BACKGROUND would silently
        // make that caller's traffic the first thing dropped under load — a
        // very quiet way to break someone.
        assertThat(Criticality.of("intractive")).isEqualTo(Criticality.NORMAL);
        assertThat(Criticality.of("")).isEqualTo(Criticality.NORMAL);
        assertThat(Criticality.of(null)).isEqualTo(Criticality.NORMAL);
    }

    @Test
    @DisplayName("Parsing is forgiving about case and whitespace")
    void parsingIsForgiving() {
        assertThat(Criticality.of("  critical ")).isEqualTo(Criticality.CRITICAL);
        assertThat(Criticality.of("Background")).isEqualTo(Criticality.BACKGROUND);
    }

    @Test
    @DisplayName("Shedding points are ordered, and only CRITICAL may overshoot")
    void sheddingOrder() {
        assertThat(Criticality.BACKGROUND.sheddingPoint())
                .isLessThan(Criticality.NORMAL.sheddingPoint());
        assertThat(Criticality.NORMAL.sheddingPoint())
                .isLessThan(Criticality.CRITICAL.sheddingPoint());
        // The limit is an estimate; being wrong about an interactive request
        // costs more than one queued call at the provider.
        assertThat(Criticality.CRITICAL.sheddingPoint()).isGreaterThan(1.0);
        assertThat(Criticality.BACKGROUND.sheddingPoint()).isLessThan(1.0);
    }
}
