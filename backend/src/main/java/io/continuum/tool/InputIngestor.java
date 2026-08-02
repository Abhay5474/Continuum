package io.continuum.tool;

import io.continuum.tool.builtin.PdfTextTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns whatever the caller sent into something the tools can use.
 *
 * <p>The step that did not exist. A pipeline's input map went straight to the
 * adapters verbatim, so a PDF was only ever an opaque base64 blob that some
 * external endpoint had to make sense of. For a born-digital PDF that is
 * unnecessary work: the text is already in the file.
 *
 * <p>Ingestion runs <b>before</b> the first tool and is deliberately narrow. It
 * does exactly two things:
 *
 * <ol>
 *   <li>recognises what was actually supplied, whatever key it arrived under;</li>
 *   <li>extracts a PDF's text layer, in process, and adds it to the input as
 *       {@code text} so every downstream tool — and the context builder — can
 *       use it.</li>
 * </ol>
 *
 * <p><b>It never replaces the original.</b> The base64 document stays in the map
 * untouched, because a downstream OCR tool needs the file itself, not the text
 * that extraction failed to find. Ingestion adds; it does not consume.
 */
@Component
public class InputIngestor {

    /** Keys a caller might put a document under. */
    private static final List<String> DOCUMENT_KEYS = List.of(
            "documentBase64", "pdfBase64", "fileBase64", "document", "pdf", "file");

    /** Keys a caller might put an image under. */
    private static final List<String> IMAGE_KEYS = List.of("imageBase64", "image");

    /** Keys a caller might put audio under. */
    private static final List<String> AUDIO_KEYS = List.of("audioBase64", "audio");

    private final PdfTextTool pdf;
    private final io.continuum.context.ContextTransformService context;

    public InputIngestor(PdfTextTool pdf, io.continuum.context.ContextTransformService context) {
        this.pdf = pdf;
        this.context = context;
    }

    /** What the caller supplied, as recognised rather than as declared. */
    public enum Detected { PDF, IMAGE, AUDIO, TEXT, JSON, NONE, STRUCTURED }

    /**
     * @param input     the map to hand to the tools — the original plus anything ingested
     * @param evidence  what ingestion itself observed; empty when it did nothing
     * @param detected  what the payload actually is
     * @param note      one line for the trace, or null when nothing happened
     */
    public record Ingested(Map<String, Object> input, List<Evidence> evidence,
                           Detected detected, String note) {

        public boolean didSomething() {
            return note != null;
        }

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("detected", detected.name());
            m.put("note", note);
            m.put("evidence", evidence.stream().map(Evidence::describe).toList());
            return m;
        }
    }

    /**
     * Recognises and, where it can, reads the input.
     *
     * <p>Never throws. An unreadable document produces a note the pipeline can
     * show, not a failed request — the developer may have an OCR tool in the
     * chain that handles exactly this case.
     */
    public Ingested ingest(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>(input == null ? Map.of() : input);

        String document = firstString(out, DOCUMENT_KEYS);
        if (document != null) {
            PdfTextTool.Result r = pdf.extractBase64(document);
            List<Evidence> ev = new ArrayList<>(r.asEvidence());

            if (r.ok()) {
                // Added under the conventional key so a text-shaped tool further
                // down the pipeline receives it without any configuration.
                out.putIfAbsent("text", r.text());
                return new Ingested(out, ev, Detected.PDF, String.format(
                        "PDF read in place: %d page%s, %d characters of text. No OCR was needed.",
                        r.pages(), r.pages() == 1 ? "" : "s", r.text().length()));
            }
            // The original document stays in the map: an OCR tool downstream
            // needs the file, not the text extraction could not find.
            return new Ingested(out, ev, Detected.PDF, r.detail());
        }

        // A spreadsheet, a log or an email thread: data whose meaning lives in
        // its structure, which the context layer recovers deterministically. Run
        // after PDF and before the media kinds, and only when the payload is
        // actually recognised — an input nothing claims falls through exactly as
        // it did before this layer existed.
        Structured structured = structured(out);
        if (structured != null) {
            out.putIfAbsent("text", structured.rendered());
            return new Ingested(out, List.of(Evidence.text(structured.label(),
                    structured.rendered(), structured.attributes())),
                    Detected.STRUCTURED, structured.note());
        }

        if (firstString(out, IMAGE_KEYS) != null) {
            return new Ingested(out, List.of(), Detected.IMAGE, null);
        }
        if (firstString(out, AUDIO_KEYS) != null) {
            return new Ingested(out, List.of(), Detected.AUDIO, null);
        }
        if (firstString(out, List.of("text")) != null) {
            return new Ingested(out, List.of(), Detected.TEXT, null);
        }
        return new Ingested(out, List.of(), out.isEmpty() ? Detected.NONE : Detected.JSON, null);
    }

    /** A recognised structured payload, already rendered for a model. */
    private record Structured(String label, String rendered, String note,
                              Map<String, Object> attributes) {
    }

    /**
     * Runs the context layer over whatever the caller sent, if it recognises it.
     *
     * <p>Returns null far more often than not, and that is the point: this must
     * be silent for every input it has no opinion about, or it changes the
     * behaviour of pipelines that were working before it existed.
     */
    private Structured structured(Map<String, Object> input) {
        for (String key : List.of("dataBase64", "fileBase64", "file", "spreadsheetBase64",
                "logs", "log", "emailBase64", "email", "text")) {
            Object v = input.get(key);
            if (!(v instanceof String s) || s.isBlank()) {
                continue;
            }
            byte[] bytes = decode(s);
            if (bytes == null || bytes.length < 32) {
                continue;
            }
            var result = context.transform(bytes, key.equals("text") ? null : key,
                    io.continuum.context.RenderBudget.standard());
            if (!result.transformed()) {
                continue;
            }
            Map<String, Object> attrs = new LinkedHashMap<>(result.context().structure());
            attrs.put("contextType", result.context().type().name());
            attrs.put("tokensBefore", result.stats().before());
            attrs.put("tokensAfter", result.stats().after());
            String note = String.format(
                    "%s recovered from the input: %s. Prompt cost %d tokens instead of %d.",
                    result.context().type().label(),
                    result.context().structure(),
                    result.stats().after(), result.stats().before());
            return new Structured(result.context().type().label(), result.rendered(), note, attrs);
        }
        return null;
    }

    /** Base64 when it is, raw UTF-8 when it is not — logs arrive both ways. */
    private static byte[] decode(String value) {
        try {
            String cleaned = value.startsWith("data:") && value.contains(",")
                    ? value.substring(value.indexOf(',') + 1) : value;
            return Base64.getDecoder().decode(cleaned.replaceAll("\\s", ""));
        } catch (RuntimeException e) {
            return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Whether a payload is a PDF, without decoding all of it. */
    public static boolean looksLikePdfBase64(String base64) {
        if (base64 == null || base64.length() < 8) {
            return false;
        }
        String head = base64.startsWith("data:") && base64.contains(",")
                ? base64.substring(base64.indexOf(',') + 1)
                : base64;
        // "%PDF-" base64-encodes to "JVBERi0" regardless of what follows.
        if (head.startsWith("JVBERi0")) {
            return true;
        }
        try {
            byte[] probe = Base64.getDecoder().decode(
                    head.substring(0, Math.min(12, head.length() / 4 * 4)));
            return probe.length >= 5 && probe[0] == '%' && probe[1] == 'P'
                    && probe[2] == 'D' && probe[3] == 'F';
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String firstString(Map<String, Object> m, List<String> keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
}
