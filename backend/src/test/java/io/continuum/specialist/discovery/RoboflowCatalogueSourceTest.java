package io.continuum.specialist.discovery;

import io.continuum.portal.TenantContext;
import io.continuum.specialist.CatalogueEntry;
import io.continuum.specialist.SpecialistConnectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The catalogue source is where a live directory meets a tenant's credential,
 * which makes it the place a key would leak if one were going to. These tests
 * are mostly about that, and about the cache — a directory cached across tenants
 * would be a cross-tenant disclosure dressed up as a performance improvement.
 */
class RoboflowCatalogueSourceTest {

    private static final String KEY = "rf_live_key_must_not_leak";

    /** Records what it was asked, so the tests can assert on the calls. */
    private static final class RecordingClient implements RoboflowDiscoveryClient {
        final List<String> queries = new ArrayList<>();
        String seenKey;
        boolean reachable = true;
        SearchResponse response;

        RecordingClient(SearchResponse response) {
            this.response = response;
        }

        @Override
        public SearchResponse search(String apiKey, String query, int limit) {
            seenKey = apiKey;
            queries.add(query);
            return response;
        }

        @Override
        public boolean reachable() {
            return reachable;
        }
    }

    private static SpecialistConnectionService withKey(String key) {
        SpecialistConnectionService svc = mock(SpecialistConnectionService.class);
        when(svc.discoveryKeyFor(anyString(), anyString()))
                .thenReturn(Optional.ofNullable(key));
        return svc;
    }

    private static RoboflowDiscoveryClient.SearchResponse oneModel() {
        return new RoboflowDiscoveryClient.SearchResponse(
                RoboflowDiscoveryClient.Status.OK,
                List.of(new RoboflowDiscoveryClient.Model("roboflow:a/b/1", "Hard Hats", "desc",
                        "a", "b", "1", "object-detection", "b/1", Map.of())),
                "1 model found.", "{}");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("searches with the tenant's stored key and returns installable entries")
    void searchesWithStoredKey() {
        TenantContext.set("dev-1");
        RecordingClient client = new RecordingClient(oneModel());
        RoboflowCatalogueSource source = new RoboflowCatalogueSource(client, withKey(KEY));

        List<CatalogueEntry> entries = source.search("hard hat", 10);

        assertThat(client.seenKey).isEqualTo(KEY);
        assertThat(entries).singleElement().satisfies(e -> {
            assertThat(e.modelPath()).isEqualTo("b/1");
            assertThat(e.needs()).containsExactly("secret");
            assertThat(e.source()).isEqualTo("Roboflow");
        });
    }

    @Test
    @DisplayName("no entry the console renders carries the API key")
    void entriesNeverCarryTheKey() {
        TenantContext.set("dev-1");
        RoboflowCatalogueSource source =
                new RoboflowCatalogueSource(new RecordingClient(oneModel()), withKey(KEY));

        String rendered = source.search("hard hat", 10).stream()
                .map(e -> e.describe().toString()).reduce("", String::concat);

        assertThat(rendered).doesNotContain(KEY);
        assertThat(String.valueOf(source.unavailableReason())).doesNotContain(KEY);
    }

    @Test
    @DisplayName("a tenant with no Roboflow credential gets a reason, not an empty list")
    void missingCredentialIsExplained() {
        TenantContext.set("dev-1");
        RoboflowCatalogueSource source =
                new RoboflowCatalogueSource(new RecordingClient(oneModel()), withKey(null));

        assertThat(source.available()).isFalse();
        // "No results" would send the developer to check their spelling. This
        // sends them to the one screen that fixes it.
        assertThat(source.unavailableReason()).contains("API key");
        assertThat(source.search("hard hat", 10)).isEmpty();
    }

    @Test
    @DisplayName("an unreachable directory is reported differently from a missing key")
    void unreachableIsItsOwnReason() {
        TenantContext.set("dev-1");
        RecordingClient client = new RecordingClient(oneModel());
        client.reachable = false;
        RoboflowCatalogueSource source = new RoboflowCatalogueSource(client, withKey(KEY));

        assertThat(source.available()).isFalse();
        assertThat(source.unavailableReason()).contains("could not be reached");
    }

    @Test
    @DisplayName("one tenant can never install an entry another tenant discovered")
    void byIdIsTenantScoped() {
        RoboflowCatalogueSource source =
                new RoboflowCatalogueSource(new RecordingClient(oneModel()), withKey(KEY));

        TenantContext.set("dev-1");
        source.search("hard hat", 10);
        assertThat(source.byId("roboflow:a/b/1")).isNotNull();

        // Directory ids are global, so an unscoped lookup map would hand dev-2
        // a result only dev-1's credential ever saw.
        TenantContext.set("dev-2");
        assertThat(source.byId("roboflow:a/b/1")).isNull();
    }

    @Test
    @DisplayName("repeat searches are cached, and Refresh drops the cache")
    void cachesAndInvalidates() {
        TenantContext.set("dev-1");
        RecordingClient client = new RecordingClient(oneModel());
        RoboflowCatalogueSource source = new RoboflowCatalogueSource(client, withKey(KEY));

        source.search("hard hat", 10);
        source.search("hard hat", 10);
        assertThat(client.queries).hasSize(1);

        source.invalidate("dev-1");
        source.search("hard hat", 10);
        assertThat(client.queries).hasSize(2);
        // A cleared cache must clear the id map too, or install would still find
        // a result the developer was told had been refreshed away.
        source.invalidate("dev-1");
        assertThat(source.byId("roboflow:a/b/1")).isNull();
    }

    @Test
    @DisplayName("an empty query never fans out to the provider")
    void browseDoesNotCallTheDirectory() {
        TenantContext.set("dev-1");
        RecordingClient client = new RecordingClient(oneModel());
        RoboflowCatalogueSource source = new RoboflowCatalogueSource(client, withKey(KEY));

        // The Hub's browse view renders on every page load. Paying a directory
        // round trip for "everything" would be slow and meaningless.
        assertThat(source.search("", 25)).isEmpty();
        assertThat(source.search(null, 25)).isEmpty();
        assertThat(client.queries).isEmpty();
    }

    @Test
    @DisplayName("a client that throws does not take the Hub down")
    void survivesADefectiveClient() {
        TenantContext.set("dev-1");
        RoboflowDiscoveryClient broken = mock(RoboflowDiscoveryClient.class);
        when(broken.search(any(), anyString(), anyInt())).thenThrow(new IllegalStateException("boom"));
        when(broken.reachable()).thenReturn(true);

        RoboflowCatalogueSource source = new RoboflowCatalogueSource(broken, withKey(KEY));

        assertThat(source.search("hard hat", 10)).isEmpty();
        verify(broken, times(1)).search(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("this source declares itself live, so the console can say so")
    void declaresItselfLive() {
        assertThat(new RoboflowCatalogueSource(null, null).live()).isTrue();
    }
}
