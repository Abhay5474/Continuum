package io.continuum.compression;

import io.continuum.provider.model.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dangerous mistake here is calling an instruction a demonstration: it
 * drops 70% of a message that told the model what to do. Most of these tests
 * are about not doing that.
 */
class CompressionPolicyTest {

    @Test
    @DisplayName("The current question is never compressed")
    void questionIsUntouched() {
        var r = CompressionPolicy.classify(Role.USER, "What is the refund policy?", true);

        assertThat(r).isEqualTo(CompressionPolicy.Region.QUESTION);
        assertThat(r.keepRatio()).isEqualTo(1.00);
    }

    @Test
    @DisplayName("A system message is treated as instructions, kept nearly intact")
    void systemIsInstruction() {
        var r = CompressionPolicy.classify(Role.SYSTEM,
                "You are a support agent. Always cite the policy number. Never guess.", false);

        assertThat(r).isEqualTo(CompressionPolicy.Region.INSTRUCTION);
        assertThat(r.keepRatio()).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    @DisplayName("A demonstration block is compressed hardest")
    void demonstrationsAreCompressedHardest() {
        // Few-shot examples are largely redundant with each other — that is what
        // makes them examples, and it is why the paper's budget gives them least.
        var r = CompressionPolicy.classify(Role.USER,
                "Example 1:\nInput: order late\nOutput: apologise, offer credit\n"
                        + "Example 2:\nInput: item damaged\nOutput: apologise, replace", false);

        assertThat(r).isEqualTo(CompressionPolicy.Region.EXAMPLE);
        assertThat(r.keepRatio()).isLessThanOrEqualTo(0.40);
    }

    @Test
    @DisplayName("One stray marker in prose is not a demonstration")
    void singleMarkerIsNotAnExample() {
        // "Output:" appears once, inside an instruction. Calling this an example
        // would throw away most of a message that set the rules.
        var r = CompressionPolicy.classify(Role.SYSTEM,
                "Answer in two paragraphs. Output: valid JSON only, no prose.", false);

        assertThat(r).isEqualTo(CompressionPolicy.Region.INSTRUCTION);
    }

    @Test
    @DisplayName("Anything unrecognised falls to the pre-existing ratio, not a harsher one")
    void unknownFallsToHistory() {
        var r = CompressionPolicy.classify(Role.ASSISTANT, "Sure, I can help with that.", false);

        assertThat(r).isEqualTo(CompressionPolicy.Region.HISTORY);
        assertThat(r.keepRatio()).isEqualTo(0.55);
    }

    @Test
    @DisplayName("Region budgets follow the paper's allocation, hardest on examples")
    void budgetOrdering() {
        assertThat(CompressionPolicy.Region.QUESTION.keepRatio())
                .isGreaterThan(CompressionPolicy.Region.INSTRUCTION.keepRatio());
        assertThat(CompressionPolicy.Region.INSTRUCTION.keepRatio())
                .isGreaterThan(CompressionPolicy.Region.HISTORY.keepRatio());
        assertThat(CompressionPolicy.Region.HISTORY.keepRatio())
                .isGreaterThan(CompressionPolicy.Region.EXAMPLE.keepRatio());
    }

    @Test
    @DisplayName("A short prompt is not compressed, and the reason says why")
    void shortPromptsAreSkipped() {
        var gate = CompressionPolicy.gate(120, null);

        assertThat(gate.compress()).isFalse();
        assertThat(gate.reason()).contains("120 tokens");
    }

    @Test
    @DisplayName("A long prompt is compressed even when the model is unknown")
    void longPromptWithUnknownModel() {
        // The gateway picks a model after this point, so there is nothing to
        // price. Size alone decides, and the reason says that rather than
        // implying a price was considered.
        var gate = CompressionPolicy.gate(5000, null);

        assertThat(gate.compress()).isTrue();
        assertThat(gate.reason()).contains("chosen later");
    }

    @Test
    @DisplayName("On a model cheap enough that the saving is negligible, it does not compress")
    void cheapModelIsNotWorthCompressing() {
        var gate = CompressionPolicy.gate(5000, 0.000001);

        assertThat(gate.compress()).isFalse();
        assertThat(gate.reason()).contains("cheap enough");
    }

    @Test
    @DisplayName("A saving worth having compresses, and says what it is worth")
    void worthwhileSavingCompresses() {
        var gate = CompressionPolicy.gate(5000, 0.02);

        assertThat(gate.compress()).isTrue();
        assertThat(gate.reason()).contains("worth about");
    }
}
