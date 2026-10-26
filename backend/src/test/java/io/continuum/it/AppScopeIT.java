package io.continuum.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.apps.AppContext;
import io.continuum.billing.BillingService;
import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two applications on one account, each with its own key: what one captures is
 * neither recalled into the other nor shown when the console looks at the other.
 */
class AppScopeIT extends PostgresIT {

    @LocalServerPort int port;
    @Autowired io.continuum.portal.PortalService portal;
    @Autowired GatewayRequestLogRepository requestLog;
    @Autowired BillingService billing;
    @Autowired ObjectMapper json;
    @Autowired io.continuum.godmode.memory.MemoryEngine memory;
    @Autowired io.continuum.persistence.repository.ExperienceNodeRepository nodes;
    @Autowired io.continuum.persistence.repository.MemoryEpisodicRepository episodic;

    private final HttpClient http = HttpClient.newHttpClient();
    private String dev;
    private String session;

    @BeforeEach
    void account() {
        var signed = portal.signup("Apps IT", "apps-" + UUID.randomUUID() + "@example.com", "correct-horse-9");
        dev = signed.developerId();
        session = signed.sessionToken();
    }

    @AfterEach
    void clearThread() {
        AppContext.clear();
    }

    private HttpResponse<String> call(String method, String path, Object body, String bearer, String app)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (app != null) {
            b.header("X-Continuum-App", app);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> issue(Map<String, Object> body) throws Exception {
        var r = call("POST", "/api/portal/developer/keys", body, session, null);
        assertThat(r.statusCode()).isEqualTo(200);
        return json.readValue(r.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String path, String app) throws Exception {
        var r = call("GET", path, null, session, app);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readValue(r.body(), List.class);
    }

    private Long appOf(Object keyId) throws Exception {
        return list("/api/portal/developer/keys", null).stream()
                .filter(k -> k.get("id").equals(keyId)).findFirst()
                .map(k -> ((Number) k.get("appId")).longValue()).orElseThrow();
    }

    private String recall(String key, String query) throws Exception {
        var r = call("POST", "/api/gateway/memory/retrieve", Map.of("query", query, "topK", 10), key, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.body();
    }

    @Test
    void eachKeyIsAnAppWithAName() throws Exception {
        var shop = issue(Map.of("label", "Brightcart"));
        var vet = issue(Map.of());

        var apps = list("/api/portal/developer/apps", null);
        assertThat(apps).extracting(a -> a.get("name")).containsExactly("Brightcart", "App 2");

        // Renaming the key renames its app, and the key list says so too.
        var renamed = call("PATCH", "/api/portal/developer/keys/" + vet.get("id"),
                Map.of("name", "Animal injury chat"), session, null);
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(200);
        assertThat(list("/api/portal/developer/apps", null)).extracting(a -> a.get("name"))
                .containsExactly("Brightcart", "Animal injury chat");
        assertThat(list("/api/portal/developer/keys", null))
                .filteredOn(k -> k.get("id").equals(vet.get("id")))
                .extracting(k -> k.get("appName"), k -> k.get("label"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Animal injury chat", "Animal injury chat"));

        // From the console, which is another origin once hosted: the browser
        // sends one, and a method missing from the CORS list is refused with 403.
        HttpRequest crossOrigin = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/portal/developer/apps/" + appOf(vet.get("id"))))
                .header("Origin", "https://continuum-console.example")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + session)
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"name\":\"PawCare\"}"))
                .build();
        assertThat(http.send(crossOrigin, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

        // A name is required, and bounded.
        assertThat(call("PATCH", "/api/portal/developer/keys/" + vet.get("id"), Map.of("name", " "),
                session, null).statusCode()).isEqualTo(400);
        assertThat(call("PATCH", "/api/portal/developer/apps/" + appOf(shop.get("id")),
                Map.of("name", "x".repeat(121)), session, null).statusCode()).isEqualTo(400);
    }

    @Test
    void oneAppNeverRecallsAnothersMemory() throws Exception {
        String shop = (String) issue(Map.of("label", "Shop")).get("apiKey");
        String vet = (String) issue(Map.of("label", "Vet")).get("apiKey");

        assertThat(call("POST", "/api/gateway/memory/store",
                Map.of("content", "Customer prefers a red silk saree for Diwali"), shop, null).statusCode())
                .isEqualTo(200);
        assertThat(call("POST", "/api/gateway/memory/store",
                Map.of("content", "The dog has a deep cut on its front paw"), vet, null).statusCode())
                .isEqualTo(200);

        assertThat(recall(shop, "saree Diwali")).contains("saree").doesNotContain("paw");
        assertThat(recall(shop, "dog paw cut")).doesNotContain("paw");
        assertThat(recall(vet, "dog paw cut")).contains("paw").doesNotContain("saree");
    }

    @Test
    void aReplacementKeyJoinsItsAppAndKeepsWhatItLearned() throws Exception {
        var first = issue(Map.of("label", "Shop"));
        String old = (String) first.get("apiKey");
        call("POST", "/api/gateway/memory/store", Map.of("content", "Ships free above 999 rupees"), old, null);

        Long app = appOf(first.get("id"));
        var second = issue(Map.of("appId", app));
        assertThat(appOf(second.get("id"))).isEqualTo(app);
        assertThat(call("DELETE", "/api/portal/developer/keys/" + first.get("id"), null, session, null)
                .statusCode()).isEqualTo(200);

        assertThat(recall((String) second.get("apiKey"), "free shipping rupees")).contains("999");
        // Only into this account's own apps.
        assertThat(call("POST", "/api/portal/developer/keys", Map.of("appId", 999_999_999L), session, null)
                .statusCode()).isEqualTo(404);
    }

    @Test
    void theConsoleShowsTheAppItIsLookingAt() throws Exception {
        Long shop = appOf(issue(Map.of("label", "Shop")).get("id"));
        Long vet = appOf(issue(Map.of("label", "Vet")).get("id"));
        save(shop, "shop-model");
        save(vet, "vet-model");
        save(null, "console-model");

        assertThat(models(null)).containsExactlyInAnyOrder("shop-model", "vet-model", "console-model");
        assertThat(models("all")).hasSize(3);
        assertThat(models(String.valueOf(shop))).containsExactly("shop-model");
        assertThat(models(String.valueOf(vet))).containsExactly("vet-model");
        assertThat(models("untagged")).containsExactly("console-model");
        // An app id that is not a number names no app: nothing, not everything.
        assertThat(models("nonsense")).isEmpty();
    }

    @Test
    void theQuotaIsTheAccountsWhicheverAppIsInView() throws Exception {
        Long shop = appOf(issue(Map.of("label", "Shop")).get("id"));
        Long vet = appOf(issue(Map.of("label", "Vet")).get("id"));
        save(shop, "a");
        save(vet, "b");

        AppContext.forConsole(String.valueOf(shop));
        // The filter is live on this thread: the log alone shows one app ...
        assertThat(requestLog.countByDeveloperIdAndSuccess(dev, true)).isEqualTo(1);
        // ... but the plan's usage is the whole account's.
        Map<String, Object> usage = billing.usage(dev);
        assertThat(((Number) usage.get("tokensUsed")).longValue()).isEqualTo(200);
        assertThat(((Number) usage.get("requestsThisPeriod")).longValue()).isEqualTo(2);
    }

    @Test
    void memoryConsolidatesWithinAnAppNeverAcrossTwo() throws Exception {
        Long shop = appOf(issue(Map.of("label", "Shop")).get("id"));
        Long vet = appOf(issue(Map.of("label", "Vet")).get("id"));
        episode(shop, "Customer asked about red silk saree sizes and delivery to Pune before the Diwali festival");
        episode(vet, "Owner described a dog limping with a deep cut on the front paw after an evening walk");

        // Consolidation runs on a background thread with no app of its own; each
        // step also rolls a learned die, so it is given a few chances to run.
        var config = new io.continuum.persistence.entity.GodModeConfigEntity(dev);
        for (int i = 0; i < 40 && nodes.countByDeveloperId(dev) < 2; i++) {
            memory.consolidate(config);
        }

        var made = nodes.findByDeveloperIdOrderByUtilityScoreDesc(dev);
        assertThat(made).hasSize(2);
        assertThat(made).allSatisfy(n ->
                assertThat(n.getAppId()).as(n.getText()).isEqualTo(n.getText().contains("saree") ? shop : vet));
    }

    private void episode(Long app, String text) {
        var e = new io.continuum.persistence.entity.MemoryEpisodicEntity(dev, "session", text, 1, 60, 40, null,
                java.time.Instant.now().plus(java.time.Duration.ofDays(1)));
        e.setAppId(app);
        episodic.save(e);
    }

    private void save(Long app, String model) {
        var e = new GatewayRequestLogEntity(dev, "auto", "gemini", model, 0.1, "test", 5, 100, 0.001, true, 0);
        e.setAppId(app);
        requestLog.save(e);
    }

    private List<Object> models(String app) throws Exception {
        return list("/api/portal/developer/requests?limit=50", app).stream()
                .map(r -> r.get("chosenModel")).toList();
    }
}
