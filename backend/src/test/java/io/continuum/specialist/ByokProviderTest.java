package io.continuum.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.persistence.entity.SpecialistConnectionEntity;
import io.continuum.tool.Evidence;
import io.continuum.tool.MediaBytes;
import io.continuum.tool.SampleMedia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bring-your-own-key adapters: Deepgram, AssemblyAI, OCR.space.
 *
 * <p><b>These do not prove the integrations work.</b> Every one of those hosts is
 * refused at CONNECT from this deployment, so no real response has been seen —
 * {@code LIVE_UNVERIFIED}. The fixtures are <em>constructed</em> from each
 * provider's documented contract; none is a captured response and none is
 * presented as one.
 *
 * <p>What they do prove is the half that is ours: that the request is shaped the
 * way the provider documents, that a transcript comes back as unscored text
 * rather than as a score something downstream would filter on, and that the
 * failure shapes are told apart instead of all collapsing into "nothing found".
 */
@SuppressWarnings("unchecked")
class ByokProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Object json(String s) throws Exception {
        return mapper.readValue(s, Object.class);
    }

    private static SpecialistConnectionEntity connection(String provider, String baseUrl) {
        return new SpecialistConnectionEntity(
                "dev-1", provider + " connection", provider, baseUrl, null, null);
    }

    /** A tiny but genuinely valid WAV, base64'd the way a caller would send it. */
    private static String wav() {
        return SampleMedia.wavBase64();
    }

    // --- Deepgram ------------------------------------------------------------

    @Nested
    class Deepgram {

        private final DeepgramProvider p = new DeepgramProvider();

        @Test
        @DisplayName("sends the audio as raw bytes, not base64 in JSON")
        void sendsRawAudio() {
            SpecialistProvider.Call call = p.buildCall(
                    connection("deepgram", null), "nova-2", Map.of("audioBase64", wav()));

            // Deepgram reads the request body as the media file. A text encoding
            // of it would reach the decoder as something that is not audio.
            assertThat(call.body()).isInstanceOf(byte[].class);
            assertThat((byte[]) call.body()).startsWith("RIFF".getBytes(StandardCharsets.UTF_8));
            assertThat(call.headers()).containsEntry("Content-Type", "audio/wav");
            assertThat(call.url()).contains("/v1/listen").contains("model=nova-2")
                    .contains("smart_format=true");
        }

        @Test
        @DisplayName("uses Authorization: Token, which is neither Bearer nor a bare header")
        void usesTokenAuthStyle() {
            assertThat(p.defaultAuthStyle())
                    .isEqualTo(SpecialistConnectionEntity.AuthStyle.TOKEN);
        }

        @Test
        @DisplayName("prefers a URL the provider can fetch itself")
        void prefersRemoteUrl() {
            SpecialistProvider.Call call = p.buildCall(connection("deepgram", null), null,
                    Map.of("audioUrl", "https://example.com/call.mp3", "audioBase64", wav()));

            assertThat(call.body()).isInstanceOf(Map.class);
            assertThat(call.headers()).containsEntry("Content-Type", "application/json");
        }

        @Test
        @DisplayName("a missing file is named before the call, not after a decode error")
        void refusesMissingAudio() {
            assertThatThrownBy(() -> p.buildCall(connection("deepgram", null), null, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("audioBase64");
        }

        @Test
        @DisplayName("a transcript is unscored text, with the recogniser confidence as metadata")
        void transcriptIsUnscored() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("""
                    {"metadata":{"duration":12.5},
                     "results":{"channels":[{"alternatives":[
                       {"transcript":"the boiler is making a knocking sound","confidence":0.93}]}]}}
                    """));

            assertThat(ev).singleElement().satisfies(e -> {
                assertThat(e.kind()).isEqualTo(Evidence.Kind.TEXT);
                assertThat(e.text()).contains("knocking sound");
                // The decisive assertion. A recogniser's certainty about its
                // wording is not a detection score, and a pipeline threshold of
                // 0.95 must not delete this transcript for being 0.93.
                assertThat(e.scored()).isFalse();
                assertThat(e.confidence()).isNull();
                assertThat(e.attributes()).containsEntry("recogniserConfidence", 0.93);
                assertThat(e.attributes()).containsEntry("durationSeconds", 12.5);
            });
        }

        @Test
        @DisplayName("silence is reported as no speech, not as an empty result")
        void silenceIsExplained() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("""
                    {"results":{"channels":[{"alternatives":[{"transcript":"","confidence":0.0}]}]}}
                    """));

            assertThat(ev).singleElement()
                    .satisfies(e -> assertThat(e.kind()).isEqualTo(Evidence.Kind.NOTE));
            assertThat(ev.get(0).text()).containsIgnoringCase("no speech");
        }

        @Test
        @DisplayName("an unexpected body yields nothing rather than throwing")
        void toleratesJunk() throws Exception {
            assertThat(p.parseEvidence(json("{}"))).isEmpty();
            assertThat(p.parseEvidence(json("[]"))).isEmpty();
            assertThat(p.parseEvidence(null)).isEmpty();
            assertThat(p.parseEvidence("not json at all")).isEmpty();
        }
    }

    // --- AssemblyAI ----------------------------------------------------------

    @Nested
    class AssemblyAI {

        private final AssemblyAIProvider p = new AssemblyAIProvider();

        @Test
        @DisplayName("stage one uploads the raw bytes")
        void stageOneUploads() {
            SpecialistProvider.Call call = p.buildCall(
                    connection("assemblyai", null), null, Map.of("audioBase64", wav()));

            assertThat(call.url()).endsWith("/v2/upload");
            assertThat(call.body()).isInstanceOf(byte[].class);
            assertThat(call.headers()).containsEntry("Content-Type", "application/octet-stream");
        }

        @Test
        @DisplayName("stage two submits the uploaded URL as a job")
        void stageTwoSubmitsJob() throws Exception {
            SpecialistProvider.Next next = p.next(connection("assemblyai", null),
                    json("{\"upload_url\":\"https://cdn.assemblyai.com/upload/abc\"}"), 1);

            assertThat(next).isNotNull();
            assertThat(next.call().url()).endsWith("/v2/transcript");
            assertThat(next.call().method()).isEqualTo("POST");
            assertThat(next.call().body()).isInstanceOf(Map.class);
            assertThat((Map<String, Object>) next.call().body())
                    .containsEntry("audio_url", "https://cdn.assemblyai.com/upload/abc");
        }

        @Test
        @DisplayName("stage three polls the job, and stops once it is terminal")
        void pollsUntilTerminal() throws Exception {
            SpecialistConnectionEntity c = connection("assemblyai", null);

            SpecialistProvider.Next queued =
                    p.next(c, json("{\"id\":\"job-1\",\"status\":\"queued\"}"), 2);
            assertThat(queued.call().url()).endsWith("/v2/transcript/job-1");
            assertThat(queued.call().method()).isEqualTo("GET");

            SpecialistProvider.Next processing =
                    p.next(c, json("{\"id\":\"job-1\",\"status\":\"processing\"}"), 4);
            // Later polls back off; the first goes out immediately because a
            // short clip is often already finished.
            assertThat(processing.delayMillis()).isGreaterThan(0);

            // Terminal either way — polling a finished job would just repeat it.
            assertThat(p.next(c, json("{\"id\":\"j\",\"status\":\"completed\",\"text\":\"hi\"}"), 5))
                    .isNull();
            assertThat(p.next(c, json("{\"id\":\"j\",\"status\":\"error\",\"error\":\"bad\"}"), 5))
                    .isNull();
        }

        @Test
        @DisplayName("a supplied URL skips the upload entirely")
        void remoteUrlSkipsUpload() {
            SpecialistProvider.Call call = p.buildCall(connection("assemblyai", null), null,
                    Map.of("audioUrl", "https://example.com/a.mp3"));

            assertThat(call.url()).endsWith("/v2/transcript");
        }

        @Test
        @DisplayName("a finished job becomes unscored text")
        void completedJobIsText() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("""
                    {"status":"completed","text":"the meeting starts at four",
                     "confidence":0.91,"audio_duration":88,"language_code":"en_us"}
                    """));

            assertThat(ev).singleElement().satisfies(e -> {
                assertThat(e.kind()).isEqualTo(Evidence.Kind.TEXT);
                assertThat(e.scored()).isFalse();
                assertThat(e.attributes()).containsEntry("recogniserConfidence", 0.91);
                assertThat(e.attributes()).containsEntry("language", "en_us");
            });
        }

        @Test
        @DisplayName("an unfinished job says so, and never looks like silence")
        void unfinishedJobIsNotSilence() throws Exception {
            List<Evidence> ev =
                    p.parseEvidence(json("{\"status\":\"processing\",\"id\":\"job-9\"}"));

            assertThat(ev).singleElement()
                    .satisfies(e -> assertThat(e.kind()).isEqualTo(Evidence.Kind.NOTE));
            // "Still running" and "the audio was silent" need opposite responses
            // from the developer, so they must not read the same.
            assertThat(ev.get(0).text()).containsIgnoringCase("still transcribing")
                    .contains("job-9");
        }

        @Test
        @DisplayName("a provider error carries the provider's own message")
        void errorCarriesMessage() throws Exception {
            List<Evidence> ev = p.parseEvidence(
                    json("{\"status\":\"error\",\"error\":\"Audio file is corrupt\"}"));

            assertThat(ev.get(0).text()).contains("Audio file is corrupt");
        }
    }

    // --- OCR.space -----------------------------------------------------------

    @Nested
    class OcrSpace {

        private final OcrSpaceProvider p = new OcrSpaceProvider();

        @Test
        @DisplayName("posts a form with a data-URL image, which is what the API reads the type from")
        void postsFormEncoded() {
            String png = Base64.getEncoder().encodeToString(
                    new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0});

            SpecialistProvider.Call call = p.buildCall(
                    connection("ocrspace", null), null, Map.of("imageBase64", png));

            assertThat(call.headers())
                    .containsEntry("Content-Type", "application/x-www-form-urlencoded");
            assertThat(call.url()).endsWith("/parse/image");
            String body = call.body().toString();
            assertThat(body).contains("base64Image=")
                    .contains("OCREngine=2")
                    .contains("detectOrientation=true");
            // URL-encoded, so the data-URL prefix appears escaped.
            assertThat(body).contains("data%3Aimage%2Fpng%3Bbase64%2C");
        }

        @Test
        @DisplayName("a scanned PDF is sent as a PDF, which is the case this adapter exists for")
        void handlesScannedPdf() {
            String pdf = SampleMedia.pdfWithTextBase64();

            SpecialistProvider.Call call = p.buildCall(
                    connection("ocrspace", null), null, Map.of("documentBase64", pdf));

            String body = call.body().toString();
            assertThat(body).contains("filetype=PDF");
            assertThat(body).contains("data%3Aapplication%2Fpdf%3Bbase64%2C");
        }

        @Test
        @DisplayName("recovered text is unscored")
        void textIsUnscored() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("""
                    {"IsErroredOnProcessing":false,"OCRExitCode":1,
                     "ParsedResults":[{"ParsedText":"INVOICE 4471\\nTotal 12500"}]}
                    """));

            assertThat(ev).singleElement().satisfies(e -> {
                assertThat(e.kind()).isEqualTo(Evidence.Kind.TEXT);
                assertThat(e.text()).contains("INVOICE 4471");
                // Recognition confidence says how clearly a character was read,
                // not whether what it says is true.
                assertThat(e.scored()).isFalse();
            });
        }

        @Test
        @DisplayName("failure reported inside a 200 body is still failure")
        void detectsInBodyFailure() throws Exception {
            // OCR.space returns HTTP 200 and puts the error in the payload, so a
            // call that "succeeded" still has to be checked.
            List<Evidence> ev = p.parseEvidence(json("""
                    {"IsErroredOnProcessing":true,
                     "ErrorMessage":["File failed to load"],"ParsedResults":null}
                    """));

            assertThat(ev).singleElement()
                    .satisfies(e -> assertThat(e.kind()).isEqualTo(Evidence.Kind.NOTE));
            assertThat(ev.get(0).text()).contains("File failed to load");
        }

        @Test
        @DisplayName("a blank page is distinguished from a failed read")
        void blankPageIsItsOwnAnswer() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("""
                    {"IsErroredOnProcessing":false,"ParsedResults":[{"ParsedText":"   "}]}
                    """));

            assertThat(ev.get(0).text()).containsIgnoringCase("no text");
        }

        @Test
        @DisplayName("multiple pages are joined, and the page count kept")
        void joinsPages() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("""
                    {"IsErroredOnProcessing":false,
                     "ParsedResults":[{"ParsedText":"page one"},{"ParsedText":"page two"}]}
                    """));

            assertThat(ev.get(0).text()).contains("page one").contains("page two");
            assertThat(ev.get(0).attributes()).containsEntry("pages", 2);
        }
    }

    // --- probe samples -------------------------------------------------------

    @Nested
    class ProbeSamples {

        @Test
        @DisplayName("the audio sample is a genuinely valid WAV, not an empty string")
        void wavIsReal() {
            byte[] wav = MediaBytes.decode(SampleMedia.wavBase64());

            assertThat(wav).isNotNull();
            assertThat(new String(wav, 0, 4, StandardCharsets.UTF_8)).isEqualTo("RIFF");
            assertThat(new String(wav, 8, 4, StandardCharsets.UTF_8)).isEqualTo("WAVE");
            // One second at 8kHz, 16-bit mono, plus the 44-byte header.
            assertThat(wav).hasSize(44 + 8000 * 2);
            assertThat(MediaBytes.audioContentType(wav)).isEqualTo("audio/wav");
        }

        @Test
        @DisplayName("the OCR sample really has text on it")
        void pngHasText() {
            byte[] png = MediaBytes.decode(SampleMedia.pngWithTextBase64());

            assertThat(png).isNotNull();
            assertThat(MediaBytes.imageContentType(png)).isEqualTo("image/png");
            // A 1x1 pixel would be a meaningless thing to ask an OCR tool to read.
            assertThat(png.length).isGreaterThan(500);
        }

        @Test
        @DisplayName("an OCR tool is probed with text, a detector with the pixel")
        void sampleFollowsTheToolKind() {
            Map<String, Object> forOcr =
                    SpecialistService.defaultSampleFor("image", io.continuum.tool.ToolKind.OCR);
            Map<String, Object> forDetection =
                    SpecialistService.defaultSampleFor("image", io.continuum.tool.ToolKind.DETECTION);

            assertThat(forOcr.get("imageBase64")).isNotEqualTo(forDetection.get("imageBase64"));
            // Detection keeps the pixel every existing detector was probed with.
            assertThat(forDetection.get("imageBase64").toString().length())
                    .isLessThan(forOcr.get("imageBase64").toString().length());
        }

        @Test
        @DisplayName("audio and documents now have samples, so those tools can be probed at all")
        void audioAndDocumentsAreProbeable() {
            assertThat(SpecialistService.defaultSampleFor("audio", null))
                    .containsKey("audioBase64");
            assertThat(SpecialistService.defaultSampleFor("document", null))
                    .containsKey("documentBase64");
        }
    }
}
