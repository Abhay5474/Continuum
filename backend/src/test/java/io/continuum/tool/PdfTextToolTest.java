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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These tests build real PDFs and read them back, so they verify extraction
 * rather than a description of it. That is possible because the tool runs in
 * process — there is no service to be unable to reach.
 *
 * <p>The distinction the tests care most about is {@code NO_TEXT_LAYER} versus
 * an empty success. Both produce no text, and they need opposite responses: one
 * is a scan that an OCR tool can rescue, the other is a document with nothing
 * in it. Collapsing them into "no text found" would make the first look
 * unfixable.
 */
class PdfTextToolTest {

    private final PdfTextTool tool = new PdfTextTool();

    /** A one-page PDF with a real text layer. */
    private static byte[] pdfWithText(String... lines) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(60, 700);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -18);
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** A PDF with pages but no text drawn on them — what a scan looks like. */
    private static byte[] pdfWithoutText(int pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("recovers the text a born-digital PDF already contains")
    void extractsText() throws Exception {
        PdfTextTool.Result r = tool.extract(pdfWithText("INVOICE 4471", "Total due 12500"));

        assertThat(r.ok()).isTrue();
        assertThat(r.outcome()).isEqualTo(PdfTextTool.Outcome.OK);
        assertThat(r.text()).contains("INVOICE 4471").contains("12500");
        assertThat(r.pages()).isEqualTo(1);
    }

    @Test
    @DisplayName("a page with no text layer is a scan needing OCR, not a blank result")
    void noTextLayerIsItsOwnOutcome() throws Exception {
        PdfTextTool.Result r = tool.extract(pdfWithoutText(3));

        assertThat(r.ok()).isFalse();
        assertThat(r.outcome()).isEqualTo(PdfTextTool.Outcome.NO_TEXT_LAYER);
        // The page count survives: knowing it is a three-page scan is what tells
        // a developer this is an OCR job rather than an empty upload.
        assertThat(r.pages()).isEqualTo(3);
        assertThat(r.detail()).containsIgnoringCase("OCR");
    }

    @Test
    @DisplayName("an unreadable document produces a note, so the pipeline can say why")
    void failureStillCarriesEvidence() {
        PdfTextTool.Result r = tool.extract("this is a PNG, honestly".getBytes());

        assertThat(r.outcome()).isEqualTo(PdfTextTool.Outcome.UNREADABLE);
        assertThat(r.asEvidence()).singleElement()
                .satisfies(e -> assertThat(e.kind()).isEqualTo(Evidence.Kind.NOTE));
    }

    @Test
    @DisplayName("extracted text is unscored evidence — there is no confidence to report")
    void evidenceIsUnscored() throws Exception {
        PdfTextTool.Result r = tool.extract(pdfWithText("Hello"));

        Evidence e = r.asEvidence().get(0);
        assertThat(e.kind()).isEqualTo(Evidence.Kind.TEXT);
        // PDFBox reports what the file says. Attaching a confidence would be
        // inventing a number, and downstream that number would be filtered on.
        assertThat(e.scored()).isFalse();
        assertThat(e.confidence()).isNull();
        assertThat(e.attributes()).containsEntry("pages", 1);
    }

    @Test
    @DisplayName("accepts base64 and data URLs, which is how browsers send a file")
    void acceptsBase64AndDataUrls() throws Exception {
        String b64 = Base64.getEncoder().encodeToString(pdfWithText("Statement 90210"));

        assertThat(tool.extractBase64(b64).text()).contains("90210");
        assertThat(tool.extractBase64("data:application/pdf;base64," + b64).text())
                .contains("90210");
    }

    @Test
    @DisplayName("a mislabelled file is named as such rather than failing inside a parser")
    void rejectsNonPdf() {
        assertThat(tool.extract(new byte[] {(byte) 0x89, 'P', 'N', 'G'}).detail())
                .contains("not a PDF");
        assertThat(tool.extractBase64("!!!not base64!!!").outcome())
                .isEqualTo(PdfTextTool.Outcome.UNREADABLE);
    }

    @Test
    @DisplayName("oversized input is refused before parsing, not after")
    void refusesOversizedInput() {
        byte[] huge = new byte[PdfTextTool.MAX_BYTES + 1];
        huge[0] = '%';
        huge[1] = 'P';
        huge[2] = 'D';
        huge[3] = 'F';
        huge[4] = '-';

        assertThat(tool.extract(huge).outcome()).isEqualTo(PdfTextTool.Outcome.TOO_LARGE);
    }
}
