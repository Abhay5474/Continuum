package io.continuum.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate already knows which kind of failure it found. The single-shot repair
 * threw that away and sent one blob of complaints; these tests pin that the kind
 * survives, and that the instruction names one thing to change.
 */
class RepairStrategyTest {

    @Test
    @DisplayName("Each defect phrase maps to the kind of fix it needs")
    void classification() {
        assertThat(RepairStrategy.classify("the answer is empty")).isEqualTo(RepairStrategy.EMPTY);
        assertThat(RepairStrategy.classify("the model refused the request"))
                .isEqualTo(RepairStrategy.REFUSAL);
        assertThat(RepairStrategy.classify("the answer was cut off mid-sentence"))
                .isEqualTo(RepairStrategy.TRUNCATION);
        assertThat(RepairStrategy.classify("asked for JSON, got prose"))
                .isEqualTo(RepairStrategy.FORMAT);
        assertThat(RepairStrategy.classify("the answer does not appear to address the question"))
                .isEqualTo(RepairStrategy.IRRELEVANT);
    }

    @Test
    @DisplayName("An unrecognised defect is kept, not dropped")
    void unclassifiedIsStillADefect() {
        // Silently ignoring a defect nobody classified would make the repair
        // engine quietly weaker than the single-shot repair it replaces.
        assertThat(RepairStrategy.classify("something nobody anticipated"))
                .isEqualTo(RepairStrategy.UNCLASSIFIED);

        RepairStrategy.Plan p = RepairStrategy.plan(List.of("something nobody anticipated"));
        assertThat(p.repairable()).isTrue();
        assertThat(p.instruction()).contains("something nobody anticipated");
    }

    @Test
    @DisplayName("A refusal is never repaired")
    void refusalIsNotRepairable() {
        // Asking again is how you get the same refusal twice and pay for both.
        RepairStrategy.Plan p = RepairStrategy.plan(
                List.of("the model refused the request", "the answer is incomplete"));

        assertThat(p.repairable()).isFalse();
        assertThat(p.instruction()).isNull();
        assertThat(p.strategies()).containsExactly(RepairStrategy.REFUSAL);
    }

    @Test
    @DisplayName("One instruction per attempt, aimed at the most severe defect")
    void oneInstructionAtATime() {
        // Asking a model to fix four things at once is how the two that were
        // already right get rewritten.
        RepairStrategy.Plan p = RepairStrategy.plan(List.of(
                "figures do not appear in the supplied context",
                "the answer was cut off mid-sentence"));

        assertThat(p.strategies().get(0)).isEqualTo(RepairStrategy.TRUNCATION);
        assertThat(p.instruction()).contains("cut off");
        assertThat(p.instruction()).doesNotContain("figures that do not appear");
        // But the caller is told there is more, so the loop knows to come back.
        assertThat(p.instruction()).contains("handled separately");
    }

    @Test
    @DisplayName("Instructions name what to change and what to leave alone")
    void instructionsAreTargeted() {
        // "Fix these problems" invites a rewrite. Naming the single change, and
        // saying explicitly not to touch the rest, is what keeps a repair from
        // undoing correct work.
        assertThat(RepairStrategy.INCOMPLETE.instruction())
                .contains("Keep what you already wrote")
                .contains("Do not rewrite");
        assertThat(RepairStrategy.FORMAT.instruction()).contains("changing nothing else");
        assertThat(RepairStrategy.UNGROUNDED.instruction()).contains("Change nothing else");
    }

    @Test
    @DisplayName("No defects means nothing to plan")
    void nothingToRepair() {
        assertThat(RepairStrategy.plan(List.of()).repairable()).isFalse();
        assertThat(RepairStrategy.plan(null).repairable()).isFalse();
    }

    @Test
    @DisplayName("Every repairable strategy has an instruction, and the others do not")
    void strategiesAreConsistent() {
        for (RepairStrategy s : RepairStrategy.values()) {
            assertThat(s.label()).as("%s", s).isNotBlank();
            if (s == RepairStrategy.REFUSAL || s == RepairStrategy.UNCLASSIFIED) {
                assertThat(s.repairable()).as("%s", s).isFalse();
            } else {
                assertThat(s.repairable()).as("%s", s).isTrue();
                assertThat(s.instruction()).as("%s", s).isNotBlank();
            }
        }
    }
}
