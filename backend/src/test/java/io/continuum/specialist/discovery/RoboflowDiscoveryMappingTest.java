package io.continuum.specialist.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.specialist.CatalogueEntry;
import io.continuum.tool.ToolKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture tests for the discovery mapping.
 *
 * <p><b>These do not prove the integration works.</b> This deployment cannot
 * reach any Roboflow host, so no response from the real service has ever been
 * seen — {@code LIVE_UNVERIFIED}. What these tests do prove is the half that is
 * ours: given a body of a particular shape, the right catalogue entry comes out,
 * and a body of an unexpected shape degrades instead of exploding.
 *
 * <p>The fixtures are deliberately labelled as <em>constructed</em>. They are
 * written from Roboflow's documented contract and from the shapes directories
 * commonly use; none of them is a captured response, and none is presented as
 * one.
 */
class RoboflowDiscoveryMappingTest {

    private final HttpRoboflowDiscoveryClient client = new HttpRoboflowDiscoveryClient(
            null, new ObjectMapper(), "https://api.roboflow.com", 10);

    private List<RoboflowDiscoveryClient.Model> map(String json) throws Exception {
        return client.map(new ObjectMapper().readValue(json, Object.class));
    }

    @Test
    @DisplayName("maps a documented-shape result into an invocable model")
    void mapsDocumentedShape() throws Exception {
        List<RoboflowDiscoveryClient.Model> models = map("""
                {"results":[{"name":"Hard Hat Detection","project":"hard-hat-sample",
                  "workspace":"joseph","type":"object-detection","version":3,
                  "description":"Detects hard hats on site."}]}
                """);

        assertThat(models).singleElement().satisfies(m -> {
            assertThat(m.name()).isEqualTo("Hard Hat Detection");
            assertThat(m.workspace()).isEqualTo("joseph");
            assertThat(m.invocable()).isTrue();
            // This is the field that decides one-click install: RoboflowProvider
            // builds "{base}/{project}/{version}" and half of that is a 404.
            assertThat(m.invocationPath()).isEqualTo("hard-hat-sample/3");
        });
    }

    @Test
    @DisplayName("a result with no version is listed but marked as needing the model path")
    void versionlessResultIsNotGuessedAt() throws Exception {
        List<RoboflowDiscoveryClient.Model> models = map("""
                {"projects":[{"name":"Wound Classifier","project":"wounds","type":"classification"}]}
                """);

        assertThat(models).singleElement().satisfies(m -> {
            assertThat(m.invocable()).isFalse();
            // Guessing "wounds/1" would produce an install that 404s on first
            // probe, which is worse than asking the developer.
            assertThat(m.invocationPath()).isNull();
        });
    }

    @Test
    @DisplayName("tolerates the field names a directory plausibly uses instead")
    void tolerantOfAliases() throws Exception {
        // snake_case, a list of version objects, a bare array instead of an
        // envelope — all shapes the documentation does not promise against.
        List<RoboflowDiscoveryClient.Model> models = map("""
                [{"display_name":"Weld Seam","project_id":"weld-seam","workspace_id":"acme",
                  "project_type":"object-detection","versions":[{"id":"1"},{"id":"7"}]}]
                """);

        assertThat(models).singleElement().satisfies(m -> {
            assertThat(m.name()).isEqualTo("Weld Seam");
            assertThat(m.workspace()).isEqualTo("acme");
            // The newest version, not the first one listed.
            assertThat(m.invocationPath()).isEqualTo("weld-seam/7");
        });
    }

    @Test
    @DisplayName("a project given as a URL yields its slug, not the whole URL")
    void extractsSlugFromUrl() throws Exception {
        List<RoboflowDiscoveryClient.Model> models = map("""
                {"models":[{"name":"Pallets","url":"https://universe.roboflow.com/acme/pallet-count",
                  "version":2}]}
                """);

        assertThat(models).singleElement()
                .satisfies(m -> assertThat(m.invocationPath()).isEqualTo("pallet-count/2"));
    }

    @Test
    @DisplayName("unrecognisable bodies produce no models rather than an exception")
    void degradesRatherThanThrows() throws Exception {
        assertThat(map("{}")).isEmpty();
        assertThat(map("[]")).isEmpty();
        assertThat(map("{\"results\":\"nope\"}")).isEmpty();
        assertThat(map("{\"results\":[1,2,3]}")).isEmpty();
        // A row with no name at all is skipped: an entry titled "null" is worse
        // for a developer than one fewer search result.
        assertThat(map("{\"results\":[{\"version\":2}]}")).isEmpty();
    }

    @Test
    @DisplayName("Roboflow's task vocabulary maps onto Continuum's tool kinds")
    void mapsTaskTypes() {
        assertThat(RoboflowCatalogueSource.kindOf("classification"))
                .isEqualTo(ToolKind.CLASSIFICATION);
        assertThat(RoboflowCatalogueSource.kindOf("single-label-classify"))
                .isEqualTo(ToolKind.CLASSIFICATION);
        assertThat(RoboflowCatalogueSource.kindOf("object-detection")).isEqualTo(ToolKind.DETECTION);
        assertThat(RoboflowCatalogueSource.kindOf("instance-segmentation"))
                .isEqualTo(ToolKind.DETECTION);
        // Unknown reads as detection, not CUSTOM: this source only ever yields
        // Roboflow vision models, and detection is both the overwhelming
        // majority and the more cautious threshold of the two.
        assertThat(RoboflowCatalogueSource.kindOf(null)).isEqualTo(ToolKind.DETECTION);
    }

    @Test
    @DisplayName("catalogue entries declare what the developer must still supply")
    void entriesDeclareWhatIsMissing() {
        RoboflowCatalogueSource source = new RoboflowCatalogueSource(null, null);

        List<CatalogueEntry> entries = source.toEntries(List.of(
                new RoboflowDiscoveryClient.Model("roboflow:a/b/1", "Ready", null, "a", "b", "1",
                        "object-detection", "b/1", java.util.Map.of()),
                new RoboflowDiscoveryClient.Model("roboflow:a/c", "Incomplete", null, "a", "c", null,
                        "classification", null, java.util.Map.of())));

        assertThat(entries.get(0).needs()).containsExactly("secret");
        assertThat(entries.get(0).modelPath()).isEqualTo("b/1");
        assertThat(entries.get(0).source()).isEqualTo("Roboflow");

        assertThat(entries.get(1).needs()).contains("modelPath");
        assertThat(entries.get(1).modelPath()).isEmpty();
        assertThat(entries.get(1).toolKind()).isEqualTo(ToolKind.CLASSIFICATION);
    }

    @Test
    @DisplayName("a search with no credential is refused before any network call")
    void refusesWithoutCredential() {
        // The sender is null, so reaching the network at all would NPE. That it
        // does not is the assertion.
        RoboflowDiscoveryClient.SearchResponse r = client.search(null, "hard hat", 10);

        assertThat(r.status()).isEqualTo(RoboflowDiscoveryClient.Status.NO_CREDENTIAL);
        assertThat(r.models()).isEmpty();
        assertThat(client.search("  ", "hard hat", 10).status())
                .isEqualTo(RoboflowDiscoveryClient.Status.NO_CREDENTIAL);
    }

    @Test
    @DisplayName("an empty query does not fan out to the provider")
    void emptyQueryDoesNotCallOut() {
        RoboflowDiscoveryClient.SearchResponse r = client.search("key-shaped-thing", "   ", 10);

        assertThat(r.models()).isEmpty();
        assertThat(r.status()).isEqualTo(RoboflowDiscoveryClient.Status.OK);
    }

    @Test
    @DisplayName("nothing the developer is shown carries the API key")
    void detailNeverCarriesTheKey() {
        String key = "rf_secret_value_do_not_leak";

        RoboflowDiscoveryClient.SearchResponse r = client.search(key, "", 5);

        assertThat(r.detail()).doesNotContain(key);
        assertThat(r.describe().toString()).doesNotContain(key);
    }
}
