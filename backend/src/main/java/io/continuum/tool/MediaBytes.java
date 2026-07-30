package io.continuum.tool;

import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pulls a media file out of a pipeline input map.
 *
 * <p>Every adapter for a provider that takes a file needs the same three things:
 * find the payload whatever key it arrived under, strip a data URL if there is
 * one, and decode it. Doing that in each adapter would mean each one having its
 * own opinion about which keys count, and a file uploaded as {@code audioBase64}
 * reaching one adapter and not another.
 */
public final class MediaBytes {

    /** Where audio plausibly is, most specific first. */
    public static final List<String> AUDIO_KEYS =
            List.of("audioBase64", "audio", "fileBase64", "file");

    /** Where an image plausibly is. */
    public static final List<String> IMAGE_KEYS =
            List.of("imageBase64", "image", "fileBase64", "file");

    /** Where a document plausibly is. */
    public static final List<String> DOCUMENT_KEYS =
            List.of("documentBase64", "pdfBase64", "document", "pdf", "fileBase64", "file");

    private MediaBytes() {
    }

    /**
     * The decoded file, or null when there isn't one.
     *
     * <p>Null rather than an exception: an adapter handed no input should report
     * that plainly to the developer, not fail somewhere inside an HTTP client.
     */
    public static byte[] find(Map<String, Object> input, List<String> keys) {
        String raw = findEncoded(input, keys);
        return raw == null ? null : decode(raw);
    }

    /** The payload still encoded, for providers that want base64 in a form field. */
    public static String findEncoded(Map<String, Object> input, List<String> keys) {
        if (input == null) {
            return null;
        }
        for (String k : keys) {
            Object v = input.get(k);
            if (v instanceof String s && !s.isBlank()) {
                return strip(s);
            }
        }
        return null;
    }

    /** Removes a {@code data:...;base64,} prefix, which browsers add. */
    public static String strip(String payload) {
        if (payload.startsWith("data:") && payload.contains(",")) {
            return payload.substring(payload.indexOf(',') + 1);
        }
        return payload;
    }

    /** Decodes, tolerating whitespace and URL-safe alphabets. Null when it cannot. */
    public static byte[] decode(String base64) {
        String cleaned = strip(base64).replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(cleaned);
        } catch (IllegalArgumentException e) {
            try {
                return Base64.getUrlDecoder().decode(cleaned);
            } catch (IllegalArgumentException e2) {
                return null;
            }
        }
    }

    /**
     * A content type guessed from the file's own bytes.
     *
     * <p>Providers that take a raw body use it to pick a decoder. Guessing from
     * a filename would be worse: the caller may not have sent one, and an
     * extension is a claim rather than evidence.
     */
    public static String audioContentType(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return "application/octet-stream";
        }
        if (bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return "audio/mpeg";
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0) {
            return "audio/mpeg";
        }
        if (bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S') {
            return "audio/ogg";
        }
        if (bytes[0] == 'f' && bytes[1] == 'L' && bytes[2] == 'a' && bytes[3] == 'C') {
            return "audio/flac";
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E') {
            return "audio/wav";
        }
        // MP4/M4A: "....ftyp" at offset 4.
        if (bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
            return "audio/mp4";
        }
        // Unrecognised is sent as octets rather than mislabelled — providers
        // sniff it themselves, and a wrong content type is worse than none.
        return "application/octet-stream";
    }

    /** An image's type, for providers that want a data URL back. */
    public static String imageContentType(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return "image/png";
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
            return "application/pdf";
        }
        return "image/png";
    }

    /** Whether a string looks like a URL a provider could fetch itself. */
    public static boolean isUrl(String value) {
        if (value == null) {
            return false;
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        return v.startsWith("http://") || v.startsWith("https://");
    }
}
