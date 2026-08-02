package io.continuum.tool;

import io.continuum.tool.builtin.PdfTextTool;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ingestion runs before the first tool, which makes it the one place that can
 * silently ruin a pipeline. These tests pin the two properties that keep it
 * safe: it never removes what the caller sent, and it never overwrites what the
 * caller already said.
 */
class InputIngestorTest {

    // A real transform service with the real transformers: ingestion's contract
    // is that structured payloads are recognised and everything else passes
    // through untouched, and a stub would not test the second half.
    private final InputIngestor ingestor = new InputIngestor(new PdfTextTool(),
            new io.continuum.context.ContextTransformService(
                    java.util.List.of(new io.continuum.context.transform.SpreadsheetTransformer(),
                            new io.continuum.context.transform.LogTransformer(),
                            new io.continuum.context.transform.EmailThreadTransformer()),
                    null));

    private static String pdfBase64(String line) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(60, 700);
                cs.showText(line);
                cs.endText();
            }
            doc.save(out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        }
    }

    @Test
    @DisplayName("a PDF is read in place and its text added under the conventional key")
    void extractsPdfText() throws Exception {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("documentBase64", pdfBase64("Policy number QX-88"));

        InputIngestor.Ingested r = ingestor.ingest(in);

        assertThat(r.detected()).isEqualTo(InputIngestor.Detected.PDF);
        assertThat(r.didSomething()).isTrue();
        assertThat(r.input().get("text").toString()).contains("QX-88");
        assertThat(r.evidence()).singleElement()
                .satisfies(e -> assertThat(e.kind()).isEqualTo(Evidence.Kind.TEXT));
    }

    @Test
    @DisplayName("the original document survives, because an OCR tool downstream needs the file")
    void neverConsumesTheOriginal() throws Exception {
        String b64 = pdfBase64("Anything");
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("documentBase64", b64);

        InputIngestor.Ingested r = ingestor.ingest(in);

        // Extraction succeeding is not a reason to throw the file away: the
        // developer may have a tool in the chain that wants the pages, not the
        // text, and ingestion cannot know that.
        assertThat(r.input()).containsEntry("documentBase64", b64);
    }

    @Test
    @DisplayName("a scanned PDF is reported as needing OCR and still carries its file forward")
    void scanIsReportedNotSwallowed() throws Exception {
        String b64;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            b64 = Base64.getEncoder().encodeToString(out.toByteArray());
        }

        InputIngestor.Ingested r = ingestor.ingest(Map.of("pdfBase64", b64));

        assertThat(r.detected()).isEqualTo(InputIngestor.Detected.PDF);
        assertThat(r.note()).containsIgnoringCase("OCR");
        assertThat(r.input()).containsKey("pdfBase64");
        assertThat(r.input()).doesNotContainKey("text");
    }

    @Test
    @DisplayName("text the caller supplied is never overwritten by extraction")
    void doesNotClobberCallerText() throws Exception {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("documentBase64", pdfBase64("From the file"));
        in.put("text", "From the caller");

        assertThat(ingestor.ingest(in).input()).containsEntry("text", "From the caller");
    }

    @Test
    @DisplayName("non-document inputs pass through untouched and record nothing")
    void passesOtherInputsThrough() {
        assertThat(ingestor.ingest(Map.of("imageBase64", "abc")).detected())
                .isEqualTo(InputIngestor.Detected.IMAGE);
        assertThat(ingestor.ingest(Map.of("audioBase64", "abc")).detected())
                .isEqualTo(InputIngestor.Detected.AUDIO);
        assertThat(ingestor.ingest(Map.of("text", "hello")).detected())
                .isEqualTo(InputIngestor.Detected.TEXT);
        assertThat(ingestor.ingest(Map.of("orderId", 4)).detected())
                .isEqualTo(InputIngestor.Detected.JSON);
        assertThat(ingestor.ingest(Map.of()).detected()).isEqualTo(InputIngestor.Detected.NONE);

        // Nothing happened, so nothing is traced — an ingestion step on every
        // JSON request would be noise in every trace.
        assertThat(ingestor.ingest(Map.of("text", "hello")).didSomething()).isFalse();
    }

    @Test
    @DisplayName("a null input map is an empty one, not a crash")
    void toleratesNull() {
        assertThat(ingestor.ingest(null).detected()).isEqualTo(InputIngestor.Detected.NONE);
    }

    @Test
    @DisplayName("PDFs are recognised by their magic bytes, not by the key they arrived under")
    void recognisesPdfPayloads() throws Exception {
        String b64 = pdfBase64("x");

        assertThat(InputIngestor.looksLikePdfBase64(b64)).isTrue();
        assertThat(InputIngestor.looksLikePdfBase64("data:application/pdf;base64," + b64)).isTrue();
        assertThat(InputIngestor.looksLikePdfBase64(
                Base64.getEncoder().encodeToString("\211PNG....".getBytes()))).isFalse();
        assertThat(InputIngestor.looksLikePdfBase64(null)).isFalse();
        assertThat(InputIngestor.looksLikePdfBase64("")).isFalse();
    }
}
