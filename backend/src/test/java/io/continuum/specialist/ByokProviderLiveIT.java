package io.continuum.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.net.GuardedHttpSender;
import io.continuum.persistence.entity.SpecialistConnectionEntity;
import io.continuum.tool.Evidence;
import io.continuum.tool.SampleMedia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tests that talk to the real Deepgram, AssemblyAI, OCR.space, Hugging Face
 * and Google Vision.
 *
 * <p><b>Every one of them is skipped in this deployment, and that is reported
 * rather than hidden.</b> All three hosts are refused at CONNECT here, so each
 * test is gated on its provider's key being in the environment and simply does
 * not run without one. A skipped test is not a passing test: the adapters'
 * status is {@code LIVE_UNVERIFIED}, and nothing in this codebase claims
 * otherwise.
 *
 * <p>Run them somewhere with network access and a free-tier key to move the
 * adapters from "correct against constructed fixtures" to "correct against the
 * service":
 *
 * <pre>
 *   DEEPGRAM_API_KEY=…      mvn test -Dtest=ByokProviderLiveIT
 *   ASSEMBLYAI_API_KEY=…    mvn test -Dtest=ByokProviderLiveIT
 *   OCRSPACE_API_KEY=…      mvn test -Dtest=ByokProviderLiveIT
 *   HUGGINGFACE_API_KEY=…   mvn test -Dtest=ByokProviderLiveIT
 *   GOOGLE_VISION_API_KEY=… mvn test -Dtest=ByokProviderLiveIT
 * </pre>
 *
 * <p>If one fails there, the fixtures in {@link ByokProviderTest} are what to
 * fix: they encode an assumption about a contract, and the service is the
 * authority. Keys are read from the environment and must never be written into
 * a source file, a fixture or a properties file.
 *
 * <p>Each test sends {@link SampleMedia}, which is a genuine WAV and a genuine
 * PNG with text on it — the same media a probe uses. That makes a pass here
 * meaningful: the provider decoded a real file.
 */
class ByokProviderLiveIT {

    private final ObjectMapper mapper = new ObjectMapper();

    private static SpecialistConnectionEntity connection(String provider) {
        return new SpecialistConnectionEntity("dev-live", provider, provider, null, null, null);
    }

    /**
     * Sends one adapter's call for real, applying auth the way the invoker does.
     *
     * <p>Deliberately mirrors {@link SpecialistInvoker} rather than calling it:
     * the invoker needs a database, a vault and a trace recorder, none of which
     * this test has or needs. What is being checked is the adapter's contract
     * with the provider.
     */
    private Object send(SpecialistProvider provider, String key, SpecialistProvider.Call call)
            throws Exception {
        GuardedHttpSender sender = new GuardedHttpSender(false);

        String url = call.url();
        var headers = new java.util.LinkedHashMap<>(call.headers());
        switch (provider.defaultAuthStyle()) {
            case BEARER -> headers.put("Authorization", "Bearer " + key);
            case TOKEN -> headers.put("Authorization", "Token " + key);
            case HEADER -> headers.put(provider.defaultAuthParam(), key);
            case QUERY -> url += (url.contains("?") ? "&" : "?")
                    + provider.defaultAuthParam() + "=" + key;
            case NONE -> { }
        }

        byte[] body = call.body() instanceof byte[] raw
                ? raw
                : (call.body() instanceof String s ? s : mapper.writeValueAsString(call.body()))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        GuardedHttpSender.Result res = sender.send(url, call.method(), headers, body, 60);
        assertThat(res.status())
                .as("%s returned HTTP %d: %s", provider.name(), res.status(), res.body())
                .isLessThan(400);
        return mapper.readValue(res.body(), Object.class);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPGRAM_API_KEY", matches = ".+")
    @DisplayName("Deepgram accepts the raw audio body and returns a parseable transcript")
    void deepgramLive() throws Exception {
        DeepgramProvider p = new DeepgramProvider();
        String key = System.getenv("DEEPGRAM_API_KEY");

        Object body = send(p, key, p.buildCall(connection("deepgram"), "nova-2",
                Map.of("audioBase64", SampleMedia.wavBase64())));

        List<Evidence> ev = p.parseEvidence(body);

        // The sample is a tone, so "no speech" is the expected and correct
        // outcome. What is being proved is that the request shape was accepted
        // and the response shape was understood — not that Deepgram transcribes
        // well, which no probe can show.
        assertThat(ev).isNotEmpty();
        assertThat(ev).allSatisfy(e -> assertThat(e.scored()).isFalse());
        assertThat(String.valueOf(body)).doesNotContain(key);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ASSEMBLYAI_API_KEY", matches = ".+")
    @DisplayName("AssemblyAI's upload, submit and poll sequence completes")
    void assemblyAiLive() throws Exception {
        AssemblyAIProvider p = new AssemblyAIProvider();
        String key = System.getenv("ASSEMBLYAI_API_KEY");
        SpecialistConnectionEntity c = connection("assemblyai");

        SpecialistProvider.Call call = p.buildCall(c, null,
                Map.of("audioBase64", SampleMedia.wavBase64()));

        // Drive the same loop the invoker drives, so the multi-round seam is
        // what is under test and not just the parsing.
        Object body = null;
        for (int round = 1; round <= p.maxRounds(); round++) {
            body = send(p, key, call);
            SpecialistProvider.Next next = p.next(c, body, round);
            if (next == null) {
                break;
            }
            if (next.delayMillis() > 0) {
                Thread.sleep(next.delayMillis());
            }
            call = next.call();
        }

        assertThat(body).isInstanceOf(Map.class);
        // Reaching a terminal state is the assertion: an unfinished job here
        // would mean the poll loop never converged, which is the failure mode
        // worth catching.
        assertThat(((Map<?, ?>) body).get("status")).isIn("completed", "error");
        assertThat(p.parseEvidence(body)).isNotEmpty();
        assertThat(String.valueOf(body)).doesNotContain(key);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HUGGINGFACE_API_KEY", matches = ".+")
    @DisplayName("Hugging Face classifies text, and the cold-start wait works")
    void huggingFaceLive() throws Exception {
        HuggingFaceProvider p = new HuggingFaceProvider();
        String key = System.getenv("HUGGINGFACE_API_KEY");

        Object body = send(p, key, p.buildCall(connection("huggingface"), "unitary/toxic-bert",
                Map.of("text", "you are a horrible person")));

        List<Evidence> ev = p.parseEvidence(body);

        // Reaching here at all is half the test: a cold model answers 503, and
        // x-wait-for-model is what turns that into a slow success rather than a
        // failure the invoker reports as a broken tool.
        assertThat(ev).isNotEmpty();
        // A classifier's scores are genuine probabilities, so these must be
        // scored — the opposite of the transcript cases above.
        assertThat(ev).anySatisfy(e -> assertThat(e.scored()).isTrue());
        assertThat(String.valueOf(body)).doesNotContain(key);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GOOGLE_VISION_API_KEY", matches = ".+")
    @DisplayName("Google Vision reads the text Continuum drew on its own sample image")
    void googleVisionLive() throws Exception {
        GoogleVisionProvider p = new GoogleVisionProvider();
        String key = System.getenv("GOOGLE_VISION_API_KEY");

        Object body = send(p, key, p.buildCall(connection("googlevision"),
                "DOCUMENT_TEXT_DETECTION",
                Map.of("imageBase64", SampleMedia.pngWithTextBase64())));

        List<Evidence> ev = p.parseEvidence(body);

        assertThat(ev).isNotEmpty();
        assertThat(ev.get(0).kind()).isEqualTo(Evidence.Kind.TEXT);
        // Known text on the probe image, so a working OCR must return it.
        assertThat(ev.get(0).text().replaceAll("\\s+", " "))
                .containsIgnoringCase("CONTINUUM PROBE");
        assertThat(String.valueOf(body)).doesNotContain(key);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OCRSPACE_API_KEY", matches = ".+")
    @DisplayName("OCR.space reads the text Continuum drew on its own sample image")
    void ocrSpaceLive() throws Exception {
        OcrSpaceProvider p = new OcrSpaceProvider();
        String key = System.getenv("OCRSPACE_API_KEY");

        Object body = send(p, key, p.buildCall(connection("ocrspace"), null,
                Map.of("imageBase64", SampleMedia.pngWithTextBase64())));

        List<Evidence> ev = p.parseEvidence(body);

        assertThat(ev).isNotEmpty();
        // Unlike the audio samples, this one has a right answer: the probe image
        // has known text drawn on it, so a working OCR must return it.
        assertThat(ev.get(0).kind()).isEqualTo(Evidence.Kind.TEXT);
        assertThat(ev.get(0).text().replaceAll("\\s+", " "))
                .containsIgnoringCase("CONTINUUM PROBE");
        assertThat(ev.get(0).scored()).isFalse();
        assertThat(String.valueOf(body)).doesNotContain(key);
    }
}
