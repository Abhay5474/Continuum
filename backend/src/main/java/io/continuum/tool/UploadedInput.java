package io.continuum.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns an uploaded file into a pipeline input map.
 *
 * <p>Before this, sending Continuum a PDF meant the caller base64'd it into JSON
 * themselves. That is a fine machine-to-machine contract and a hostile one for a
 * person testing a pipeline in the console, who has a file on their desk and no
 * reason to own a base64 encoder.
 *
 * <p>The one judgement here is which key a file lands under, because that is
 * what decides which tools see it. It is taken from the bytes where possible and
 * the declared content type otherwise — a browser that says
 * {@code application/octet-stream} for a PDF is common, and trusting it would
 * route a perfectly readable document to nothing at all.
 */
public final class UploadedInput {

    /** Refused before anything is read into memory. */
    public static final long MAX_BYTES = 25L * 1024 * 1024;

    private UploadedInput() {
    }

    /** A file that cannot be accepted, with a reason written for the developer. */
    public static class RejectedException extends IllegalArgumentException {
        public RejectedException(String message) {
            super(message);
        }
    }

    /**
     * Builds the input map for a run.
     *
     * @param file   the upload; required
     * @param extra  additional JSON input, merged underneath the file. Optional,
     *               and it may not override the file's own key — a caller who
     *               uploads a PDF and also passes {@code documentBase64} would
     *               otherwise get whichever the parser happened to apply last
     */
    public static Map<String, Object> from(MultipartFile file, String extraJson,
                                           ObjectMapper mapper) {
        if (file == null || file.isEmpty()) {
            throw new RejectedException("No file was uploaded.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new RejectedException(String.format(
                    "That file is %.1fMB and the limit is %dMB.",
                    file.getSize() / 1024.0 / 1024.0, MAX_BYTES / 1024 / 1024));
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new RejectedException("The upload could not be read.");
        }

        Map<String, Object> input = new LinkedHashMap<>();
        if (extraJson != null && !extraJson.isBlank()) {
            try {
                Map<?, ?> parsed = mapper.readValue(extraJson, Map.class);
                parsed.forEach((k, v) -> {
                    if (k != null) {
                        input.put(k.toString(), v);
                    }
                });
            } catch (Exception e) {
                throw new RejectedException("The extra input field is not valid JSON.");
            }
        }

        String key = keyFor(bytes, file.getContentType(), file.getOriginalFilename());
        if ("text".equals(key)) {
            // Text is put in as text rather than base64: everything downstream
            // wants to read it, and encoding it would only mean decoding it.
            input.put("text", new String(bytes, StandardCharsets.UTF_8));
        } else {
            input.put(key, Base64.getEncoder().encodeToString(bytes));
        }
        input.put("filename", file.getOriginalFilename() == null ? "upload"
                : file.getOriginalFilename());
        return input;
    }

    /**
     * Which input key a file belongs under.
     *
     * <p>Magic bytes first. A content type is what the client claims; the bytes
     * are what arrived, and when they disagree the bytes are right.
     */
    static String keyFor(byte[] bytes, String contentType, String filename) {
        if (startsWith(bytes, new byte[] {'%', 'P', 'D', 'F', '-'})) {
            return "documentBase64";
        }
        if (startsWith(bytes, new byte[] {(byte) 0x89, 'P', 'N', 'G'})
                || startsWith(bytes, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})
                || startsWith(bytes, new byte[] {'G', 'I', 'F', '8'})) {
            return "imageBase64";
        }
        if (startsWith(bytes, new byte[] {'I', 'D', '3'})
                || startsWith(bytes, new byte[] {'O', 'g', 'g', 'S'})
                || startsWith(bytes, new byte[] {'f', 'L', 'a', 'C'})) {
            return "audioBase64";
        }
        // RIFF....WAVE — the marker that matters is four bytes in.
        if (startsWith(bytes, new byte[] {'R', 'I', 'F', 'F'}) && bytes.length > 11
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E') {
            return "audioBase64";
        }

        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.startsWith("image/")) {
            return "imageBase64";
        }
        if (type.startsWith("audio/") || type.startsWith("video/")) {
            return "audioBase64";
        }
        if (type.contains("pdf")) {
            return "documentBase64";
        }
        if (type.startsWith("text/") || type.contains("json") || type.contains("csv")) {
            return "text";
        }

        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        for (String ext : List.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp")) {
            if (name.endsWith(ext)) {
                return "imageBase64";
            }
        }
        for (String ext : List.of(".mp3", ".wav", ".m4a", ".ogg", ".flac", ".mp4")) {
            if (name.endsWith(ext)) {
                return "audioBase64";
            }
        }
        if (name.endsWith(".pdf")) {
            return "documentBase64";
        }
        if (name.endsWith(".txt") || name.endsWith(".csv") || name.endsWith(".json")
                || name.endsWith(".md")) {
            return "text";
        }

        // Unrecognised, so treated as a document: it is the key ingestion looks
        // at, and being told "this is not a PDF" is more useful than a file that
        // silently reached no tool at all.
        return "documentBase64";
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
