package io.continuum.specialist;

import io.continuum.persistence.entity.SpecialistConnectionEntity;
import io.continuum.tool.Evidence;
import io.continuum.tool.MediaBytes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCR.space — an image or a scanned PDF in, its text out.
 *
 * <p>The developer brings their own OCR.space key. This is the adapter that
 * completes the document story: {@code PdfTextTool} reads a born-digital PDF for
 * free and in process, and reports {@code NO_TEXT_LAYER} when it meets a scan.
 * A scan is exactly what this handles — and because OCR.space accepts PDFs
 * directly, the same pipeline covers both without the developer choosing in
 * advance which kind of document they are about to receive.
 *
 * <p>Chosen as the first OCR adapter over Google Vision or Azure because it is
 * one form-encoded POST with an API key. The enterprise options need a cloud
 * project, a service account and a signing flow before they return a single
 * character.
 *
 * <p><b>Text is unscored evidence.</b> OCR.space reports what it read, and its
 * per-word confidences are about character recognition, not about whether
 * something is true. Attaching one would let a pipeline threshold silently
 * delete a page. Where the provider gives an overall figure it is kept as an
 * attribute, which the console shows and no filter acts on.
 *
 * <p><b>LIVE_UNVERIFIED.</b> Written against OCR.space's documented parse API
 * and never run against the real service — {@code api.ocr.space} is refused at
 * CONNECT here. Parsing is covered by fixture tests; the network path is not.
 */
public class OcrSpaceProvider implements SpecialistProvider {

    /**
     * Engine 2 handles rotated and lower-quality scans noticeably better than
     * the default, which is the case that reaches OCR at all.
     */
    private static final String DEFAULT_ENGINE = "2";

    @Override
    public String name() {
        return "ocrspace";
    }

    @Override
    public String label() {
        return "OCR.space (image and PDF to text)";
    }

    @Override
    public String defaultBaseUrl() {
        return "https://api.ocr.space";
    }

    @Override
    public SpecialistConnectionEntity.AuthStyle defaultAuthStyle() {
        return SpecialistConnectionEntity.AuthStyle.HEADER;
    }

    @Override
    public String defaultAuthParam() {
        return "apikey";
    }

    @Override
    public List<String> inputKinds() {
        return List.of("image", "document");
    }

    @Override
    public Call buildCall(SpecialistConnectionEntity connection, String modelPath,
                          Map<String, Object> input) {
        String base = connection.getBaseUrl() == null || connection.getBaseUrl().isBlank()
                ? defaultBaseUrl()
                : connection.getBaseUrl().replaceAll("/+$", "");

        StringBuilder form = new StringBuilder();
        form.append("OCREngine=").append(DEFAULT_ENGINE)
                .append("&isOverlayRequired=false")
                .append("&scale=true")
                // Auto-rotates a page that was scanned sideways, which is common
                // enough that leaving it off would look like a broken adapter.
                .append("&detectOrientation=true");

        Object language = input.get("language");
        form.append("&language=").append(language instanceof String s && !s.isBlank()
                ? URLEncoder.encode(s.strip(), StandardCharsets.UTF_8) : "eng");

        // A URL the provider fetches itself avoids pushing the whole file
        // through Continuum, so it wins when the caller supplied one.
        Object url = input.get("imageUrl");
        if (url instanceof String s && MediaBytes.isUrl(s)) {
            form.append("&url=").append(URLEncoder.encode(s.strip(), StandardCharsets.UTF_8));
        } else {
            // Documents first: a scanned PDF is the case this adapter exists for,
            // and it arrives under a document key rather than an image one.
            byte[] bytes = MediaBytes.find(input, MediaBytes.DOCUMENT_KEYS);
            if (bytes == null) {
                bytes = MediaBytes.find(input, MediaBytes.IMAGE_KEYS);
            }
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException(
                        "No image or document was supplied. Send it as \"imageBase64\" or "
                                + "\"documentBase64\", or give \"imageUrl\" for a file OCR.space "
                                + "can fetch itself.");
            }
            String type = MediaBytes.imageContentType(bytes);
            String encoded = MediaBytes.findEncoded(input, MediaBytes.DOCUMENT_KEYS);
            if (encoded == null) {
                encoded = MediaBytes.findEncoded(input, MediaBytes.IMAGE_KEYS);
            }
            // OCR.space requires the data-URL prefix: it reads the file type
            // from it rather than sniffing the bytes.
            form.append("&base64Image=").append(URLEncoder.encode(
                    "data:" + type + ";base64," + encoded, StandardCharsets.UTF_8));
            if (type.equals("application/pdf")) {
                form.append("&filetype=PDF");
            }
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        return new Call(base + "/parse/image", "POST", headers, form.toString());
    }

    @Override
    public List<Finding> parse(Object responseBody) {
        return List.of();
    }

    @Override
    public List<Evidence> parseEvidence(Object responseBody) {
        if (!(responseBody instanceof Map<?, ?> body)) {
            return List.of();
        }

        // OCR.space reports failure in the 200 body rather than in the status,
        // so a call that "succeeded" still has to be checked.
        if (Boolean.TRUE.equals(body.get("IsErroredOnProcessing"))) {
            return List.of(Evidence.note("OCR.space could not read this file"
                    + describeError(body.get("ErrorMessage")) + "."));
        }

        Object results = body.get("ParsedResults");
        if (!(results instanceof List<?> list) || list.isEmpty()) {
            return List.of(Evidence.note(
                    "OCR.space returned no parsed results for this file."));
        }

        List<Evidence> out = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        int pages = 0;

        for (Object o : list) {
            if (!(o instanceof Map<?, ?> page)) {
                continue;
            }
            pages++;
            if (page.get("ParsedText") instanceof String text && !text.isBlank()) {
                if (!full.isEmpty()) {
                    full.append('\n');
                }
                full.append(text.strip());
            }
        }

        if (full.isEmpty()) {
            // A blank page and a failed read look the same in an empty string.
            // The distinction is what tells a developer whether to fix the file
            // or the pipeline.
            return List.of(Evidence.note(
                    "OCR.space read the file and found no text. The page may be blank, or the "
                            + "image may be too low-resolution to recognise."));
        }

        Map<String, Object> attrs = new LinkedHashMap<>();
        if (pages > 1) {
            attrs.put("pages", pages);
        }
        out.add(Evidence.text("Recognised text", full.toString(), attrs));
        return out;
    }

    /** OCR.space sends its error as either a string or a list of them. */
    private static String describeError(Object message) {
        if (message instanceof String s && !s.isBlank()) {
            return ": " + s.strip();
        }
        if (message instanceof List<?> list && !list.isEmpty() && list.get(0) != null) {
            return ": " + list.get(0).toString().strip();
        }
        return "";
    }
}
