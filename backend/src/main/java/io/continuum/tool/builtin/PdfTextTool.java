package io.continuum.tool.builtin;

import io.continuum.tool.Evidence;
import io.continuum.tool.ToolKind;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the text a PDF already contains.
 *
 * <p>The first tool in Continuum that runs <b>inside</b> the process rather than
 * against a developer's endpoint, and the justification is specific: a
 * born-digital PDF — an invoice, a statement, a contract exported from Word —
 * carries its text as text. Sending it to an OCR service would rasterise
 * something already machine-readable, pay a vendor for it, and introduce
 * recognition errors into a document that had none.
 *
 * <p>Before this existed, a PDF could only reach Continuum by being base64'd
 * into JSON by the caller and routed to an OCR endpoint the developer had to run
 * themselves. Neither half of that was necessary.
 *
 * <p><b>What it deliberately does not do.</b> It does not OCR. A scanned page is
 * an image of text, and PDFBox will correctly return nothing for it — which this
 * class reports as {@link Outcome#NO_TEXT_LAYER} rather than as an empty
 * success. That distinction is the whole point: "this PDF has no text layer, you
 * need OCR" and "this PDF is blank" look identical in an empty string and need
 * completely different fixes.
 */
@Component
public class PdfTextTool {

    private static final Logger log = LoggerFactory.getLogger(PdfTextTool.class);

    /** PDF files begin with this. Checked so a mislabelled upload fails clearly. */
    private static final byte[] MAGIC = {'%', 'P', 'D', 'F', '-'};

    /**
     * Hard ceiling on decoded input.
     *
     * <p>A PDF is a compressed container: a few megabytes can expand to an
     * enormous page count, and extraction is quadratic in the worst case. The
     * limit is on the file, before parsing, because after parsing is too late.
     */
    public static final int MAX_BYTES = 20 * 1024 * 1024;

    /** Pages beyond this are not read; the result says how many were skipped. */
    public static final int MAX_PAGES = 200;

    public enum Outcome {
        /** Text was recovered. */
        OK,
        /** Parsed cleanly and contains no text layer — almost certainly a scan. */
        NO_TEXT_LAYER,
        /** Encrypted with a password we do not have. */
        ENCRYPTED,
        /** Not a PDF, or corrupt. */
        UNREADABLE,
        /** Larger than {@link #MAX_BYTES}. */
        TOO_LARGE
    }

    /**
     * @param text      recovered text, empty unless {@link Outcome#OK}
     * @param pages     pages in the document
     * @param pagesRead pages actually extracted
     * @param detail    written for the developer, never a stack trace
     */
    public record Result(Outcome outcome, String text, int pages, int pagesRead, String detail) {

        public boolean ok() {
            return outcome == Outcome.OK;
        }

        /**
         * As tool evidence.
         *
         * <p>{@link Evidence.Kind#TEXT}, unscored — PDFBox reports what the file
         * says, not how confident it is, because there is nothing to be
         * confident about. A page count travels alongside so the model can be
         * told when it is seeing part of a document.
         */
        public List<Evidence> asEvidence() {
            if (!ok()) {
                // A note rather than silence: the pipeline must be able to say
                // "this needs OCR" instead of "nothing was found".
                return List.of(Evidence.note(detail));
            }
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("pages", pages);
            if (pagesRead < pages) {
                attrs.put("pagesRead", pagesRead);
            }
            return List.of(Evidence.text("PDF", text, attrs));
        }

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("outcome", outcome.name());
            m.put("pages", pages);
            m.put("pagesRead", pagesRead);
            m.put("characters", text == null ? 0 : text.length());
            m.put("detail", detail);
            return m;
        }
    }

    public ToolKind kind() {
        return ToolKind.OCR;
    }

    /** Extracts from a base64 payload, which is how the JSON path supplies one. */
    public Result extractBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            return new Result(Outcome.UNREADABLE, "", 0, 0, "No document was supplied.");
        }
        byte[] bytes;
        try {
            // Tolerate data URLs and whitespace; both are common from browsers.
            String cleaned = base64.contains(",") && base64.startsWith("data:")
                    ? base64.substring(base64.indexOf(',') + 1)
                    : base64;
            bytes = Base64.getDecoder().decode(cleaned.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            return new Result(Outcome.UNREADABLE, "", 0, 0,
                    "The document could not be decoded — it is not valid base64.");
        }
        return extract(bytes);
    }

    /** Extracts from raw bytes, which is how the upload path supplies one. */
    public Result extract(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new Result(Outcome.UNREADABLE, "", 0, 0, "No document was supplied.");
        }
        if (bytes.length > MAX_BYTES) {
            return new Result(Outcome.TOO_LARGE, "", 0, 0, String.format(
                    "The document is %.1fMB and the limit is %dMB.",
                    bytes.length / 1024.0 / 1024.0, MAX_BYTES / 1024 / 1024));
        }
        if (!looksLikePdf(bytes)) {
            // Named rather than guessed at: a JPEG sent to a PDF tool should say
            // so, not fail somewhere inside a parser.
            return new Result(Outcome.UNREADABLE, "", 0, 0,
                    "This is not a PDF — the file does not begin with %PDF-.");
        }

        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int pages = doc.getNumberOfPages();
            int read = Math.min(pages, MAX_PAGES);

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(read);
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);

            String trimmed = text == null ? "" : text.strip();
            if (trimmed.isEmpty()) {
                return new Result(Outcome.NO_TEXT_LAYER, "", pages, read,
                        "This PDF has no text layer — it is almost certainly a scan or a set of "
                                + "images. Extraction cannot read it; an OCR tool can. Add one to "
                                + "the pipeline and it will run on the same input.");
            }

            String detail = read < pages
                    ? String.format("Read %d of %d pages; the rest were not extracted.", read, pages)
                    : String.format("Read all %d page%s.", pages, pages == 1 ? "" : "s");
            return new Result(Outcome.OK, trimmed, pages, read, detail);

        } catch (InvalidPasswordException e) {
            return new Result(Outcome.ENCRYPTED, "", 0, 0,
                    "This PDF is password-protected. Continuum will not attempt to open it.");
        } catch (Exception e) {
            // The library's own message can carry file internals; the developer
            // gets a cause, not a stack trace.
            log.debug("PDF extraction failed: {}", e.toString());
            return new Result(Outcome.UNREADABLE, "", 0, 0,
                    "The document could not be read. It may be corrupt or use an unsupported "
                            + "PDF feature.");
        }
    }

    private static boolean looksLikePdf(byte[] b) {
        if (b.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (b[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /** Everything this tool can produce, for the console. */
    public static List<Map<String, Object>> outcomes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Outcome o : Outcome.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("outcome", o.name());
            m.put("recoverable", o == Outcome.NO_TEXT_LAYER);
            out.add(m);
        }
        return out;
    }
}
