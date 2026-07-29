package io.continuum.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole point of this type is that "unscored" is a third thing, distinct
 * from a high confidence and from a low one. Most of these tests defend that.
 */
class EvidenceTest {

    @Test
    @DisplayName("Recovered text carries no confidence, and none is invented for it")
    void textIsUnscored() {
        var e = Evidence.text(null, "INVOICE 4471", Map.of());

        assertThat(e.scored()).isFalse();
        assertThat(e.confidence()).isNull();
        assertThat(e.describe().get("confidence")).isNull();
        assertThat(e.describe().get("scored")).isEqualTo(false);
    }

    @Test
    @DisplayName("Unscored evidence survives normalisation")
    void normaliseKeepsUnscored() {
        // Dropping it would delete the entire output of every OCR, transcription
        // and extraction tool in the system.
        var out = Evidence.normalise(List.of(
                Evidence.text(null, "hello", Map.of()),
                Evidence.field("total", "12500", null)));

        assertThat(out).hasSize(2);
    }

    @Test
    @DisplayName("A percentage-scaled confidence is divided, not clamped")
    void percentageScale() {
        var out = Evidence.normalise(List.of(Evidence.classification("toxic", 91)));

        assertThat(out).singleElement()
                .extracting(Evidence::confidence).isEqualTo(0.91);
    }

    @Test
    @DisplayName("A confidence outside any plausible scale is dropped, never clamped")
    void impossibleConfidenceIsDropped() {
        // Clamping 12500 to 1.0 turns an invoice total into maximum certainty
        // and defeats every threshold downstream.
        assertThat(Evidence.normalise(List.of(Evidence.classification("total", 12500)))).isEmpty();
        assertThat(Evidence.normalise(List.of(Evidence.classification("x", -1)))).isEmpty();
        assertThat(Evidence.normalise(List.of(Evidence.classification("x", Double.NaN)))).isEmpty();
    }

    @Test
    @DisplayName("Scored evidence sorts before unscored, strongest first")
    void ordering() {
        var out = Evidence.normalise(List.of(
                Evidence.text(null, "some text", Map.of()),
                Evidence.classification("weak", 0.2),
                Evidence.classification("strong", 0.9)));

        assertThat(out).extracting(Evidence::kind).containsExactly(
                Evidence.Kind.CLASSIFICATION, Evidence.Kind.CLASSIFICATION, Evidence.Kind.TEXT);
        assertThat(out.get(0).label()).isEqualTo("strong");
    }

    @Test
    @DisplayName("Only scored, labelled evidence can be projected back to a Finding")
    void findingShape() {
        // Anything else would have to invent a label or a confidence to fit,
        // and inventing a confidence is what this class exists to stop.
        assertThat(Evidence.detection("wound", 0.9, Map.of("x", 1)).isFindingShaped()).isTrue();
        assertThat(Evidence.classification("toxic", 0.9).isFindingShaped()).isTrue();
        assertThat(Evidence.text(null, "hello", Map.of()).isFindingShaped()).isFalse();
        assertThat(Evidence.field("total", "10", null).isFindingShaped()).isFalse();
        assertThat(Evidence.row(Map.of("a", 1)).isFindingShaped()).isFalse();
    }

    @Test
    @DisplayName("A tool kind knows whether it produces confidences at all")
    void toolKindsDeclareScoring() {
        assertThat(ToolKind.DETECTION.isScored()).isTrue();
        assertThat(ToolKind.MODERATION.isScored()).isTrue();
        assertThat(ToolKind.OCR.isScored()).isFalse();
        assertThat(ToolKind.TRANSCRIPTION.isScored()).isFalse();
        assertThat(ToolKind.EXTRACTION.isScored()).isFalse();
    }

    @Test
    @DisplayName("An unrecognised tool kind reads as CUSTOM, the kind that assumes least")
    void unknownKind() {
        assertThat(ToolKind.of("detektion")).isEqualTo(ToolKind.CUSTOM);
        assertThat(ToolKind.of(null)).isEqualTo(ToolKind.CUSTOM);
        assertThat(ToolKind.of(" ocr ")).isEqualTo(ToolKind.OCR);
    }
}
