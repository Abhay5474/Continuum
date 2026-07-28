package io.continuum.specialist;

import io.continuum.persistence.entity.SpecialistConnectionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The adapters exist so that everything downstream — the context builder, the
 * confidence policy, the prompt — sees one shape regardless of which provider
 * produced it. These tests pin that normalisation, and pin that a surprising
 * response produces no findings rather than an exception.
 */
class SpecialistProviderTest {

    private final RoboflowProvider roboflow = new RoboflowProvider();
    private final GenericHttpProvider http = new GenericHttpProvider();

    private static SpecialistConnectionEntity connection(String provider, String baseUrl) {
        return new SpecialistConnectionEntity("dev-1", "test", provider, baseUrl,
                SpecialistConnectionEntity.AuthStyle.QUERY, "api_key");
    }

    // --- Roboflow -----------------------------------------------------------

    @Test
    @DisplayName("Roboflow detections become normalised findings, strongest first")
    void roboflowParsesDetections() {
        Object body = Map.of("predictions", List.of(
                Map.of("class", "bruise", "confidence", 0.62, "x", 10, "y", 20, "width", 5, "height", 6),
                Map.of("class", "wound", "confidence", 0.87, "x", 30, "y", 40, "width", 8, "height", 9)));

        List<SpecialistProvider.Finding> findings = roboflow.parse(body);

        assertThat(findings).hasSize(2);
        // The top finding is what the prompt leads with, so ordering is not cosmetic.
        assertThat(findings.get(0).label()).isEqualTo("wound");
        assertThat(findings.get(0).confidence()).isEqualTo(0.87);
        assertThat(findings.get(0).region()).containsEntry("x", 30);
    }

    @Test
    @DisplayName("an unexpected Roboflow response yields no findings rather than throwing")
    void roboflowToleratesSurprises() {
        assertThat(roboflow.parse(null)).isEmpty();
        assertThat(roboflow.parse("service temporarily unavailable")).isEmpty();
        assertThat(roboflow.parse(Map.of("error", "bad model"))).isEmpty();
        assertThat(roboflow.parse(Map.of("predictions", "not a list"))).isEmpty();
        // A prediction missing its class is skipped, not fatal.
        assertThat(roboflow.parse(Map.of("predictions", List.of(Map.of("confidence", 0.9))))).isEmpty();
    }

    @Test
    @DisplayName("the credential goes in a query parameter, which is Roboflow's shape")
    void roboflowAuthStyle() {
        assertThat(roboflow.defaultAuthStyle()).isEqualTo(SpecialistConnectionEntity.AuthStyle.QUERY);
        assertThat(roboflow.defaultAuthParam()).isEqualTo("api_key");
    }

    @Test
    @DisplayName("the confidence threshold is pushed down to the provider, not applied after")
    void roboflowPassesThresholdDown() {
        var call = roboflow.buildCall(connection("roboflow", "https://detect.roboflow.com"),
                "animal-injury/3", Map.of("imageBase64", "AAAA", "minConfidence", 0.7));

        // Filtering at the provider avoids paying to transfer detections that
        // are only going to be discarded.
        assertThat(call.url()).contains("animal-injury/3").contains("confidence=70");
        assertThat(call.body()).isEqualTo("AAAA");
        assertThat(call.headers()).containsEntry("Content-Type", "application/x-www-form-urlencoded");
    }

    @Test
    @DisplayName("a trailing slash on the base URL does not produce a double slash")
    void roboflowJoinsUrlCleanly() {
        var call = roboflow.buildCall(connection("roboflow", "https://detect.roboflow.com/"),
                "/animal-injury/3", Map.of("imageBase64", "x"));

        assertThat(call.url()).doesNotContain("//animal").contains("/animal-injury/3");
    }

    // --- generic HTTP -------------------------------------------------------

    @Test
    @DisplayName("a custom endpoint's list of predictions is understood")
    void httpParsesPredictionList() {
        var findings = http.parse(Map.of("results", List.of(
                Map.of("label", "cracked", "score", 0.4),
                Map.of("label", "dented", "score", 0.9))));

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).label()).isEqualTo("dented");
    }

    @Test
    @DisplayName("a single top-level result is understood")
    void httpParsesSingleResult() {
        var findings = http.parse(Map.of("label", "wound", "confidence", 0.75));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).confidence()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("a bare label-to-score map is understood")
    void httpParsesScoreMap() {
        var findings = http.parse(Map.of("wound", 0.87, "bruise", 0.12));

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).label()).isEqualTo("wound");
    }

    @Test
    @DisplayName("a bare list of labels carries no confidence, so it is not reported as zero")
    void httpUnscoredLabels() {
        var findings = http.parse(List.of("wound", "bleeding"));

        // Zero would read as "certainly not present", which is the opposite of
        // "the endpoint did not tell us".
        assertThat(findings).hasSize(2);
        assertThat(findings).allMatch(f -> f.confidence() == 1.0);
    }

    @Test
    @DisplayName("an unparseable response yields nothing rather than throwing")
    void httpToleratesSurprises() {
        assertThat(http.parse(null)).isEmpty();
        assertThat(http.parse("plain text")).isEmpty();
        assertThat(http.parse(Map.of("unrelated", Map.of("nested", "thing")))).isEmpty();
    }

    @Test
    @DisplayName("a custom endpoint has no default home, so one must be supplied")
    void httpRequiresBaseUrl() {
        assertThat(http.defaultBaseUrl()).isNull();
    }

    @Test
    @DisplayName("a provider that knows its own parameter name does not make the developer type it")
    void providerSuppliesItsOwnAuthParam() {
        // The first cut validated the raw input before applying this fallback,
        // so creating a Roboflow connection the obvious way was rejected for
        // omitting a value the adapter already knew.
        assertThat(roboflow.defaultAuthParam()).isNotNull();
        assertThat(http.defaultAuthParam()).isNull();
    }

    // --- registry -----------------------------------------------------------

    @Test
    @DisplayName("an unknown provider is refused with the known ones listed")
    void unknownProviderIsRefused() {
        assertThatThrownBy(() -> SpecialistProviders.byName("nope"))
                .isInstanceOf(SpecialistConnectionService.InvalidConnectionException.class)
                .hasMessageContaining("roboflow");
    }

    @Test
    @DisplayName("the catalogue says which providers need a base URL")
    void catalogueFlagsRequiredBaseUrl() {
        var catalogue = SpecialistProviders.catalogue();

        assertThat(catalogue).anySatisfy(m -> {
            assertThat(m.get("name")).isEqualTo("roboflow");
            assertThat(m.get("requiresBaseUrl")).isEqualTo(false);
        });
        assertThat(catalogue).anySatisfy(m -> {
            assertThat(m.get("name")).isEqualTo("http");
            assertThat(m.get("requiresBaseUrl")).isEqualTo(true);
        });
    }

    // --- confidence range, found by driving a live pipeline ------------------

    @Test
    @DisplayName("An empty results list means nothing found, not 'scrape the envelope'")
    void emptyResultsListIsTheAnswer() {
        // The bug: a detector reporting no predictions fell through to the
        // score-map fallback, which read the envelope's own bookkeeping fields
        // as findings. "bytes_received: 128" reached the confidence policy as
        // strong evidence and produced confident advice about nothing.
        List<SpecialistProvider.Finding> findings = http.parse(Map.of(
                "predictions", List.of(),
                "bytes_received", 128,
                "credential_seen", true));

        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("A percentage-scaled score is brought onto 0-1")
    void percentageScaleIsNormalised() {
        // An endpoint scoring out of 100 would otherwise clear the reporting
        // threshold, the prose bands and the policy in one go.
        List<SpecialistProvider.Finding> out = SpecialistProvider.normalise(List.of(
                new SpecialistProvider.Finding("wound", 85, null)));

        assertThat(out).singleElement()
                .satisfies(f -> assertThat(f.confidence()).isEqualTo(0.85));
    }

    @Test
    @DisplayName("A confidence outside any recognisable scale is dropped, not clamped")
    void impossibleConfidenceIsDropped() {
        // Clamping 128 to 1.0 would turn garbage into maximum confidence, which
        // is the failure this check exists to stop.
        assertThat(SpecialistProvider.normalise(List.of(
                new SpecialistProvider.Finding("bytes_received", 128, null),
                new SpecialistProvider.Finding("negative", -0.5, null),
                new SpecialistProvider.Finding("nan", Double.NaN, null))))
                .isEmpty();
    }

    @Test
    @DisplayName("Normal 0-1 confidences pass through untouched, strongest first")
    void normalConfidencesAreUnchanged() {
        List<SpecialistProvider.Finding> out = SpecialistProvider.normalise(List.of(
                new SpecialistProvider.Finding("bruise", 0.42, null),
                new SpecialistProvider.Finding("wound", 0.91, null)));

        assertThat(out).extracting(SpecialistProvider.Finding::label)
                .containsExactly("wound", "bruise");
        assertThat(out.get(0).confidence()).isEqualTo(0.91);
    }

    @Test
    @DisplayName("Exactly 1.0 stays full confidence rather than becoming 1%")
    void oneIsFullConfidence() {
        // The one genuinely ambiguous value. A bare list of labels already uses
        // 1.0 to mean "unscored", so reading it as 1% would silently filter
        // every unscored finding out.
        assertThat(SpecialistProvider.normalise(List.of(
                new SpecialistProvider.Finding("wound", 1.0, null))).get(0).confidence())
                .isEqualTo(1.0);
    }
}
