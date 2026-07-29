package io.continuum.specialist;

import io.continuum.tool.Evidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every one of these bodies produced <b>zero</b> findings before the evidence
 * model existed, which is why the OCR, transcription and document-extraction
 * entries in the Hub installed cleanly and contributed nothing to a prompt.
 *
 * <p>These are the exact shapes measured against the old parser, kept as tests
 * so the regression cannot come back.
 */
class GenericHttpProviderEvidenceTest {

    private final GenericHttpProvider provider = new GenericHttpProvider();

    private List<Evidence> parse(Object body) {
        return provider.parseEvidence(body);
    }

    @Test
    @DisplayName("OCR output under any of the usual text keys becomes text evidence")
    void ocrText() {
        for (String key : List.of("text", "full_text", "extracted_text", "content")) {
            var out = parse(Map.of(key, "INVOICE 4471 total $12,500"));
            assertThat(out).as(key).singleElement()
                    .returns(Evidence.Kind.TEXT, Evidence::kind)
                    .returns(false, Evidence::scored);
            assertThat(out.get(0).text()).contains("INVOICE 4471");
        }
    }

    @Test
    @DisplayName("A bare text/plain body is the whole answer")
    void plainTextBody() {
        // Some OCR and transcription endpoints do not answer JSON at all.
        var out = parse("the patient reports chest pain");

        assertThat(out).singleElement().returns(Evidence.Kind.TEXT, Evidence::kind);
        assertThat(out.get(0).text()).isEqualTo("the patient reports chest pain");
    }

    @Test
    @DisplayName("A transcript with segments is not duplicated into the prompt")
    void transcriptWithSegments() {
        var out = parse(Map.of("text", "hello there", "segments",
                List.of(Map.of("start", 0, "end", 2, "text", "hello"),
                        Map.of("start", 2, "end", 4, "text", "there"))));

        // Adding the segments as well would repeat every word already present.
        assertThat(out).singleElement().returns(Evidence.Kind.TEXT, Evidence::kind);
        assertThat(out.get(0).text()).isEqualTo("hello there");
    }

    @Test
    @DisplayName("Transcript metadata travels with the text")
    void transcriptMetadata() {
        var out = parse(Map.of("text", "hello", "language", "en", "duration", 12.5));

        assertThat(out.get(0).attributes()).containsEntry("language", "en");
        assertThat(out.get(0).attributes()).containsEntry("duration", 12.5);
    }

    @Test
    @DisplayName("A wrapped field map becomes one piece of evidence per field")
    void wrappedFields() {
        var out = parse(Map.of("fields", Map.of("invoiceNumber", "4471", "total", "12500")));

        assertThat(out).hasSize(2).allMatch(e -> e.kind() == Evidence.Kind.FIELD);
        assertThat(out).extracting(Evidence::label)
                .containsExactlyInAnyOrder("invoiceNumber", "total");
    }

    @Test
    @DisplayName("A flat extraction map is fields, not confidences")
    void flatFieldsAreNotScores() {
        // The score-map heuristic used to claim this, read the invoice total as
        // a confidence of 12500, and then lose the whole response when
        // normalisation correctly dropped it.
        var out = parse(Map.of("invoiceNumber", "4471", "total", 12500));

        assertThat(out).hasSize(2).allMatch(e -> e.kind() == Evidence.Kind.FIELD);
        assertThat(out).noneMatch(Evidence::scored);
    }

    @Test
    @DisplayName("A genuine score map is still read as classifications")
    void scoreMapStillWorks() {
        var out = parse(Map.of("toxicity", 0.91, "threat", 0.02));

        assertThat(out).hasSize(2).allMatch(e -> e.kind() == Evidence.Kind.CLASSIFICATION);
        assertThat(out.get(0).confidence()).isEqualTo(0.91);
    }

    @Test
    @DisplayName("Tabular rows become row evidence")
    void rows() {
        var out = parse(Map.of("rows", List.of(
                Map.of("sku", "A1", "qty", 3), Map.of("sku", "B2", "qty", 7))));

        assertThat(out).hasSize(2).allMatch(e -> e.kind() == Evidence.Kind.ROW);
        assertThat(out.get(0).attributes()).containsEntry("sku", "A1");
    }

    @Test
    @DisplayName("Detections keep their existing precedence and shape")
    void detectionsUnchanged() {
        var out = parse(Map.of("predictions",
                List.of(Map.of("class", "wound", "confidence", 0.87, "x", 10, "y", 20))));

        assertThat(out).singleElement()
                .returns(Evidence.Kind.DETECTION, Evidence::kind)
                .returns("wound", Evidence::label);
        assertThat(out.get(0).attributes()).containsKeys("x", "y");
    }

    @Test
    @DisplayName("A recognised but empty results list is still the answer")
    void emptyResultsListWins() {
        // Falling through here is what once reported the envelope's own
        // "bytes_received" field as a finding at 128.
        assertThat(parse(Map.of("predictions", List.of(), "bytes_received", 128))).isEmpty();
    }

    @Test
    @DisplayName("A bare list of labels is unscored rather than certain")
    void bareLabelsAreUnscored() {
        // These used to be recorded at 1.0, asserting a certainty the provider
        // never claimed.
        var out = parse(Map.of("labels", List.of("invoice", "receipt")));

        assertThat(out).hasSize(2).noneMatch(Evidence::scored);
    }

    @Test
    @DisplayName("An unreadable body yields nothing rather than throwing")
    void unreadableBody() {
        assertThat(parse(null)).isEmpty();
        assertThat(parse(42)).isEmpty();
        assertThat(parse(Map.of("nested", Map.of("deep", Map.of("x", 1))))).isEmpty();
    }
}
