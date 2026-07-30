package io.continuum.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.persistence.entity.SpecialistConnectionEntity;
import io.continuum.tool.Evidence;
import io.continuum.tool.SampleMedia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hugging Face and Google Vision.
 *
 * <p><b>{@code LIVE_UNVERIFIED} — neither host is reachable from this
 * deployment.</b> The fixtures are <em>constructed</em> from each provider's
 * documented contract; none is a captured response.
 *
 * <p>These two adapters exist mostly to answer one question each. Hugging Face:
 * can a single adapter serve a provider whose response shape changes with the
 * model's task, without being told which task it is? Google Vision: what happens
 * when a provider answers in words rather than numbers?
 */
@SuppressWarnings("unchecked")
class HuggingFaceGoogleVisionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Object json(String s) throws Exception {
        return mapper.readValue(s, Object.class);
    }

    private static SpecialistConnectionEntity connection(String provider) {
        return new SpecialistConnectionEntity("dev-1", provider, provider, null, null, null);
    }

    // --- Hugging Face --------------------------------------------------------

    @Nested
    class HuggingFace {

        private final HuggingFaceProvider p = new HuggingFaceProvider();

        @Test
        @DisplayName("builds the model URL and asks the provider to wait for a cold model")
        void buildsModelUrl() {
            SpecialistProvider.Call call = p.buildCall(connection("huggingface"),
                    "unitary/toxic-bert", Map.of("text", "you are terrible"));

            assertThat(call.url()).endsWith("/models/unitary/toxic-bert");
            // Without this a cold model answers 503 and the invoker reports a
            // working tool as broken.
            assertThat(call.headers()).containsEntry("x-wait-for-model", "true");
            assertThat((Map<String, Object>) call.body()).containsEntry("inputs", "you are terrible");
        }

        @Test
        @DisplayName("a missing model id is refused — there is no sensible default")
        void requiresModelId() {
            assertThatThrownBy(() -> p.buildCall(connection("huggingface"), null,
                    Map.of("text", "hello")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("model id");
        }

        @Test
        @DisplayName("text goes as JSON, an image goes as raw bytes")
        void picksBodyShapeFromTheInput() {
            SpecialistProvider.Call text = p.buildCall(connection("huggingface"),
                    "distilbert/x", Map.of("text", "hello"));
            assertThat(text.body()).isInstanceOf(Map.class);
            assertThat(text.headers()).containsEntry("Content-Type", "application/json");

            SpecialistProvider.Call image = p.buildCall(connection("huggingface"),
                    "google/vit-base", Map.of("imageBase64", SampleMedia.pngWithTextBase64()));
            assertThat(image.body()).isInstanceOf(byte[].class);
            assertThat(image.headers()).containsEntry("Content-Type", "image/png");
        }

        @Test
        @DisplayName("candidate labels turn a request into zero-shot classification")
        void supportsZeroShot() {
            SpecialistProvider.Call call = p.buildCall(connection("huggingface"), "facebook/bart",
                    Map.of("text", "my parcel never arrived",
                            "candidateLabels", List.of("delivery", "billing")));

            Map<String, Object> body = (Map<String, Object>) call.body();
            assertThat((Map<String, Object>) body.get("parameters"))
                    .containsEntry("candidate_labels", List.of("delivery", "billing"));
        }

        @Test
        @DisplayName("classifier scores are real probabilities, so they are scored evidence")
        void classificationIsScored() throws Exception {
            // The batch form: an outer array for the inputs, of which we sent one.
            List<Evidence> ev = p.parseEvidence(json("""
                    [[{"label":"toxic","score":0.94},{"label":"neutral","score":0.06}]]
                    """));

            assertThat(ev).hasSize(2);
            assertThat(ev.get(0).kind()).isEqualTo(Evidence.Kind.CLASSIFICATION);
            assertThat(ev.get(0).label()).isEqualTo("toxic");
            // Unlike a transcript's confidence, this one genuinely is what a
            // threshold exists to compare against.
            assertThat(ev.get(0).scored()).isTrue();
            assertThat(ev.get(0).confidence()).isEqualTo(0.94);
        }

        @Test
        @DisplayName("the unbatched form works too, since not every model returns one")
        void handlesUnbatchedForm() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("[{\"label\":\"cat\",\"score\":0.8}]"));

            assertThat(ev).singleElement()
                    .satisfies(e -> assertThat(e.label()).isEqualTo("cat"));
        }

        @Test
        @DisplayName("a box turns a classification into a detection, keeping the region")
        void detectionKeepsItsBox() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("""
                    [{"label":"person","score":0.99,
                      "box":{"xmin":10,"ymin":20,"xmax":100,"ymax":200}}]
                    """));

            assertThat(ev).singleElement().satisfies(e -> {
                assertThat(e.kind()).isEqualTo(Evidence.Kind.DETECTION);
                assertThat(e.attributes()).containsEntry("xmin", 10);
            });
        }

        @Test
        @DisplayName("speech from the same provider is unscored, unlike its classifiers")
        void asrIsUnscored() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("{\"text\":\"the meeting is at four\"}"));

            assertThat(ev).singleElement().satisfies(e -> {
                assertThat(e.kind()).isEqualTo(Evidence.Kind.TEXT);
                // The distinction the whole adapter turns on: same provider,
                // same endpoint shape, and this one must not be filtered.
                assertThat(e.scored()).isFalse();
            });
        }

        @Test
        @DisplayName("zero-shot's parallel arrays become classifications")
        void zeroShotParses() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("""
                    {"sequence":"my parcel never arrived",
                     "labels":["delivery","billing"],"scores":[0.97,0.03]}
                    """));

            assertThat(ev).hasSize(2);
            assertThat(ev.get(0).label()).isEqualTo("delivery");
            assertThat(ev.get(0).confidence()).isEqualTo(0.97);
        }

        @Test
        @DisplayName("generated text comes back as text")
        void generationParses() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("[{\"summary_text\":\"a short summary\"}]"));

            assertThat(ev).singleElement().satisfies(e -> {
                assertThat(e.kind()).isEqualTo(Evidence.Kind.TEXT);
                assertThat(e.text()).isEqualTo("a short summary");
            });
        }

        @Test
        @DisplayName("an error in a 200 body is reported, since a bad model id arrives that way")
        void reportsInBodyError() throws Exception {
            List<Evidence> ev = p.parseEvidence(
                    json("{\"error\":\"Model nope/nope does not exist\"}"));

            assertThat(ev).singleElement()
                    .satisfies(e -> assertThat(e.kind()).isEqualTo(Evidence.Kind.NOTE));
            assertThat(ev.get(0).text()).contains("does not exist");
        }

        @Test
        @DisplayName("an unrecognised shape yields nothing rather than throwing")
        void toleratesJunk() throws Exception {
            assertThat(p.parseEvidence(json("{}"))).isEmpty();
            assertThat(p.parseEvidence(json("[]"))).isEmpty();
            assertThat(p.parseEvidence(json("[{\"nonsense\":1}]"))).isEmpty();
            assertThat(p.parseEvidence(null)).isEmpty();
        }
    }

    // --- Google Vision -------------------------------------------------------

    @Nested
    class GoogleVision {

        private final GoogleVisionProvider p = new GoogleVisionProvider();

        @Test
        @DisplayName("posts an annotate request whose feature comes from the model path")
        void buildsAnnotateRequest() {
            String png = Base64.getEncoder().encodeToString(
                    new byte[] {(byte) 0x89, 'P', 'N', 'G'});

            SpecialistProvider.Call call = p.buildCall(connection("googlevision"),
                    "LABEL_DETECTION", Map.of("imageBase64", png));

            assertThat(call.url()).endsWith("/v1/images:annotate");
            Map<String, Object> body = (Map<String, Object>) call.body();
            List<Map<String, Object>> requests =
                    (List<Map<String, Object>>) body.get("requests");
            List<Map<String, Object>> features =
                    (List<Map<String, Object>>) requests.get(0).get("features");
            assertThat(features.get(0)).containsEntry("type", "LABEL_DETECTION");
        }

        @Test
        @DisplayName("authenticates with a query-string API key, not a service account")
        void usesApiKeyAuth() {
            // The reason this adapter is approachable: no JWT signing, no gcloud.
            assertThat(p.defaultAuthStyle())
                    .isEqualTo(SpecialistConnectionEntity.AuthStyle.QUERY);
            assertThat(p.defaultAuthParam()).isEqualTo("key");
        }

        @Test
        @DisplayName("OCR returns the whole page, not the word-by-word list")
        void ocrPrefersFullText() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("""
                    {"responses":[{"fullTextAnnotation":{"text":"INVOICE 4471\\nTotal 12500"},
                     "textAnnotations":[{"description":"INVOICE"},{"description":"4471"}]}]}
                    """));

            assertThat(ev).singleElement().satisfies(e -> {
                assertThat(e.kind()).isEqualTo(Evidence.Kind.TEXT);
                // Layout preserved is more useful to a model than a bag of words.
                assertThat(e.text()).contains("INVOICE 4471").contains("12500");
                assertThat(e.scored()).isFalse();
            });
        }

        @Test
        @DisplayName("plain text detection falls back to the first annotation")
        void ocrFallsBackToAnnotations() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("""
                    {"responses":[{"textAnnotations":[
                      {"description":"STOP"},{"description":"S"}]}]}
                    """));

            assertThat(ev.get(0).text()).isEqualTo("STOP");
        }

        @Test
        @DisplayName("labels are scored, because those really are probabilities")
        void labelsAreScored() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("""
                    {"responses":[{"labelAnnotations":[
                      {"description":"Dog","score":0.98},{"description":"Pet","score":0.91}]}]}
                    """));

            assertThat(ev).hasSize(2);
            assertThat(ev.get(0).kind()).isEqualTo(Evidence.Kind.CLASSIFICATION);
            assertThat(ev.get(0).scored()).isTrue();
        }

        @Test
        @DisplayName("safe search keeps Google's words rather than inventing scores for them")
        void safeSearchStaysCategorical() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("""
                    {"responses":[{"safeSearchAnnotation":{
                      "adult":"VERY_UNLIKELY","violence":"POSSIBLE","racy":"LIKELY"}}]}
                    """));

            assertThat(ev).hasSize(3);
            // There is no defensible number for "POSSIBLE". Turning it into 0.5
            // would invent the precision a moderation rule then compares against.
            assertThat(ev).allSatisfy(e -> {
                assertThat(e.kind()).isEqualTo(Evidence.Kind.FIELD);
                assertThat(e.scored()).isFalse();
            });
            assertThat(ev).anySatisfy(e -> {
                assertThat(e.label()).isEqualTo("violence");
                assertThat(e.text()).isEqualTo("POSSIBLE");
            });
        }

        @Test
        @DisplayName("a per-image error inside the response is surfaced")
        void reportsPerImageError() throws Exception {
            // Vision reports these with HTTP 200, so the status alone is not
            // enough to know the call worked.
            List<Evidence> ev = p.parseEvidence(json("""
                    {"responses":[{"error":{"code":3,"message":"Bad image data"}}]}
                    """));

            assertThat(ev).singleElement()
                    .satisfies(e -> assertThat(e.kind()).isEqualTo(Evidence.Kind.NOTE));
            assertThat(ev.get(0).text()).contains("Bad image data");
        }

        @Test
        @DisplayName("an empty response says so, instead of looking like a failed call")
        void emptyResponseIsExplained() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("{\"responses\":[{}]}"));

            assertThat(ev).singleElement()
                    .satisfies(e -> assertThat(e.kind()).isEqualTo(Evidence.Kind.NOTE));
            assertThat(ev.get(0).text()).containsIgnoringCase("found nothing");
        }

        @Test
        @DisplayName("a rejected key is reported from the top-level error")
        void reportsTopLevelError() throws Exception {
            List<Evidence> ev = p.parseEvidence(json("""
                    {"error":{"code":403,"message":"API key not valid"}}
                    """));

            assertThat(ev.get(0).text()).contains("API key not valid");
        }
    }
}
