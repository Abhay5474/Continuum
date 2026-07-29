package io.continuum.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which key an uploaded file lands under decides which tools ever see it, so
 * these tests are really about routing rather than about parsing.
 *
 * <p>The rule they pin is that the bytes beat the declaration. Browsers send
 * {@code application/octet-stream} for PDFs often enough that trusting the
 * content type would strand readable documents.
 */
class UploadedInputTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static MockMultipartFile file(String name, String type, byte[] bytes) {
        return new MockMultipartFile("file", name, type, bytes);
    }

    @Test
    @DisplayName("a PDF is recognised by its bytes even when the type is wrong")
    void bytesBeatTheDeclaredType() {
        byte[] pdf = "%PDF-1.7\nnot really".getBytes(StandardCharsets.UTF_8);

        Map<String, Object> in = UploadedInput.from(
                file("mystery.bin", "application/octet-stream", pdf), null, mapper);

        assertThat(in).containsKey("documentBase64");
        assertThat(Base64.getDecoder().decode(in.get("documentBase64").toString())).isEqualTo(pdf);
        assertThat(in).containsEntry("filename", "mystery.bin");
    }

    @Test
    @DisplayName("images and audio land under the keys their tools read")
    void routesImagesAndAudio() {
        assertThat(UploadedInput.from(
                file("x.png", null, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0}), null, mapper))
                .containsKey("imageBase64");
        assertThat(UploadedInput.from(
                file("x.jpg", null, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}), null, mapper))
                .containsKey("imageBase64");
        assertThat(UploadedInput.from(
                file("x.mp3", null, new byte[] {'I', 'D', '3', 4, 0}), null, mapper))
                .containsKey("audioBase64");
        assertThat(UploadedInput.keyFor(new byte[0], null, "clip.wav")).isEqualTo("audioBase64");
    }

    @Test
    @DisplayName("text arrives as text, not as base64 something has to decode again")
    void textIsNotEncoded() {
        Map<String, Object> in = UploadedInput.from(
                file("notes.txt", "text/plain", "hello there".getBytes(StandardCharsets.UTF_8)),
                null, mapper);

        assertThat(in).containsEntry("text", "hello there");
    }

    @Test
    @DisplayName("extra JSON input is merged, and cannot displace the uploaded file")
    void extraInputCannotDisplaceTheFile() {
        byte[] pdf = "%PDF-1.4 real".getBytes(StandardCharsets.UTF_8);

        Map<String, Object> in = UploadedInput.from(
                file("a.pdf", "application/pdf", pdf),
                "{\"documentBase64\":\"c29tZXRoaW5nIGVsc2U=\",\"orderId\":42}", mapper);

        assertThat(in).containsEntry("orderId", 42);
        // The upload is the thing the caller actually attached; a stale field in
        // the JSON must not quietly win.
        assertThat(Base64.getDecoder().decode(in.get("documentBase64").toString())).isEqualTo(pdf);
    }

    @Test
    @DisplayName("an unusable upload is refused with a reason, not a stack trace")
    void refusesBadUploads() {
        assertThatThrownBy(() -> UploadedInput.from(null, null, mapper))
                .isInstanceOf(UploadedInput.RejectedException.class)
                .hasMessageContaining("No file");

        assertThatThrownBy(() -> UploadedInput.from(
                file("a.pdf", "application/pdf", "%PDF-".getBytes()), "not json", mapper))
                .isInstanceOf(UploadedInput.RejectedException.class)
                .hasMessageContaining("valid JSON");
    }

    @Test
    @DisplayName("an unrecognised file is treated as a document so ingestion can name the problem")
    void unknownFallsBackToDocument() {
        // Better than dropping it: ingestion will say "this is not a PDF",
        // which is actionable, rather than the file reaching no tool at all.
        assertThat(UploadedInput.keyFor(new byte[] {1, 2, 3}, null, "thing.xyz"))
                .isEqualTo("documentBase64");
    }
}
