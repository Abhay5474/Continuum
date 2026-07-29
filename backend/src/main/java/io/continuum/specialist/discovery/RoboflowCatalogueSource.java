package io.continuum.specialist.discovery;

import io.continuum.portal.TenantContext;
import io.continuum.specialist.CatalogueEntry;
import io.continuum.specialist.CatalogueSource;
import io.continuum.specialist.SpecialistConnectionService;
import io.continuum.tool.ToolKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Hub's live Roboflow directory.
 *
 * <p>Discovery only. It finds models and turns them into catalogue entries; it
 * does not call one, parse one, or store a credential. Installing an entry goes
 * through the existing Hub install flow, which creates a connection through the
 * existing vault and a specialist that the existing {@code RoboflowProvider}
 * invokes. <b>There is deliberately no second Roboflow execution path.</b>
 *
 * <p><b>The credential is the tenant's own, and it is already stored.</b>
 * Roboflow's directory needs an API key, and asking for a second one purely to
 * search would be both irritating and a second place for a secret to live. This
 * source reads the key from the tenant's existing Roboflow connection through
 * the vault — the same key their models are called with. A tenant with no
 * Roboflow connection gets a source marked unavailable with that as the reason,
 * which is actionable, rather than an empty result list, which is not.
 *
 * <p><b>Live status: unverified from this deployment.</b> Every Roboflow host is
 * refused at CONNECT here, so no search has ever returned. The mapping is
 * covered by fixture tests; the network path is not, and is marked as such in
 * the source list the console renders.
 */
@Component
public class RoboflowCatalogueSource implements CatalogueSource {

    private static final Logger log = LoggerFactory.getLogger(RoboflowCatalogueSource.class);

    /** How long a search is reused before the provider is asked again. */
    public static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /** Most cached searches kept, per process. Small: this is a convenience. */
    private static final int MAX_CACHED = 200;

    private final RoboflowDiscoveryClient client;
    private final SpecialistConnectionService connections;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * Entries recently returned, by id, so an install can find what a search
     * showed. Keyed by tenant as well as id: one tenant must never install
     * against something another tenant's credential discovered.
     */
    private final Map<String, CatalogueEntry> recent = new ConcurrentHashMap<>();

    @Override
    public boolean live() {
        return true;
    }

    /**
     * A cached search.
     *
     * <p>Holds entries only. It must never hold the key used to obtain them —
     * caching a decrypted secret to save a vault read would trade a real
     * security property for a negligible one.
     */
    private record Cached(List<CatalogueEntry> entries, RoboflowDiscoveryClient.Status status,
                          String detail, Instant at) {
        boolean fresh() {
            return Instant.now().isBefore(at.plus(CACHE_TTL));
        }
    }

    public RoboflowCatalogueSource(RoboflowDiscoveryClient client,
                                   SpecialistConnectionService connections) {
        this.client = client;
        this.connections = connections;
    }

    @Override
    public String name() {
        return "Roboflow";
    }

    /**
     * Available means "could answer a search right now", which needs both a
     * credential and a reachable host — and the two failures need different
     * advice, so {@link #unavailableReason()} says which it is.
     */
    @Override
    public boolean available() {
        return credentialFor(TenantContext.developerId()) != null && client.reachable();
    }

    /** Why the source cannot answer, for the console's source list. */
    @Override
    public String unavailableReason() {
        if (credentialFor(TenantContext.developerId()) == null) {
            return "No Roboflow API key is connected. Add a Roboflow connection and the Hub will "
                    + "search Roboflow's directory with the same key it calls your models with.";
        }
        if (!client.reachable()) {
            return "Roboflow's directory could not be reached from this deployment.";
        }
        return null;
    }

    @Override
    public List<CatalogueEntry> search(String query, int limit) {
        String developerId = TenantContext.developerId();
        String key = credentialFor(developerId);
        if (key == null) {
            return List.of();
        }

        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            // The Hub's browse view must not fan out to a provider directory:
            // there is no meaningful "everything" and the round trip would be
            // paid on every empty page load.
            return List.of();
        }

        String cacheKey = developerId + "|" + q + "|" + limit;
        Cached hit = cache.get(cacheKey);
        if (hit != null && hit.fresh()) {
            return hit.entries();
        }

        RoboflowDiscoveryClient.SearchResponse res;
        try {
            res = client.search(key, q, limit);
        } catch (RuntimeException e) {
            // The interface forbids throwing; a defective implementation must
            // still not take the Hub down.
            log.debug("Roboflow discovery threw: {}", e.toString());
            return List.of();
        }

        List<CatalogueEntry> entries = res.ok() ? toEntries(res.models()) : List.of();
        if (cache.size() > MAX_CACHED) {
            cache.clear();
        }
        cache.put(cacheKey, new Cached(entries, res.status(), res.detail(), Instant.now()));
        remember(developerId, entries);
        return entries;
    }

    /**
     * An entry a search showed this tenant, so the install a moment later can
     * find it.
     *
     * <p>Scoped by tenant on purpose. Ids from a live directory are global, so
     * an unscoped map would let one tenant install an entry that only another
     * tenant's credential had ever discovered.
     */
    @Override
    public CatalogueEntry byId(String id) {
        return id == null ? null : recent.get(TenantContext.developerId() + "|" + id);
    }

    private void remember(String developerId, List<CatalogueEntry> entries) {
        if (recent.size() > MAX_CACHED) {
            recent.clear();
        }
        for (CatalogueEntry e : entries) {
            recent.put(developerId + "|" + e.id(), e);
        }
    }

    /** The last outcome for a query, so the console can explain an empty result. */
    public String lastDetail(String developerId, String query, int limit) {
        Cached c = cache.get(developerId + "|"
                + (query == null ? "" : query.strip().toLowerCase(Locale.ROOT)) + "|" + limit);
        return c == null ? null : c.detail();
    }

    /**
     * Turns discovered models into installable entries.
     *
     * <p>The one judgement here is {@code needs}. A model whose project and
     * version both came back can be installed in one click, so it needs only a
     * credential. One that did not is still listed — it is a real model and the
     * developer may know its path — but it declares {@code modelPath} as
     * required, which makes the Hub ask for it instead of installing something
     * that will 404.
     */
    List<CatalogueEntry> toEntries(List<RoboflowDiscoveryClient.Model> models) {
        List<CatalogueEntry> out = new ArrayList<>();
        for (RoboflowDiscoveryClient.Model m : models) {
            ToolKind kind = kindOf(m.taskType());
            boolean invocable = m.invocable();

            List<String> needs = invocable ? List.of("secret") : List.of("modelPath", "secret");
            String note = invocable
                    ? "Discovered on Roboflow. Continuum already knows the project and version, so "
                            + "it only needs your API key — or an existing Roboflow connection to "
                            + "reuse."
                    : "Discovered on Roboflow, but the directory did not return a version for this "
                            + "project. Paste the model path — it looks like project-name/3 — "
                            + "because guessing it would produce an install that 404s.";

            List<String> tags = new ArrayList<>(List.of("roboflow", "vision", "live"));
            if (m.taskType() != null) {
                tags.add(m.taskType().toLowerCase(Locale.ROOT));
            }
            if (m.workspace() != null) {
                tags.add(m.workspace().toLowerCase(Locale.ROOT));
            }

            out.add(new CatalogueEntry(
                    m.id(),
                    m.name(),
                    m.description() == null || m.description().isBlank()
                            ? "A Roboflow model discovered in the public directory."
                            : m.description(),
                    "roboflow",
                    kind == ToolKind.CLASSIFICATION
                            ? "https://classify.roboflow.com"
                            : "https://detect.roboflow.com",
                    invocable ? m.invocationPath() : "",
                    "image",
                    kind,
                    kind == ToolKind.CLASSIFICATION ? 0.50 : 0.40,
                    tags,
                    needs,
                    note,
                    name()));
        }
        return out;
    }

    /**
     * Roboflow's task vocabulary onto Continuum's.
     *
     * <p>Unknown reads as DETECTION rather than CUSTOM: this source only ever
     * yields Roboflow vision models, and detection is both the overwhelming
     * majority and the reading whose threshold is the more cautious of the two.
     */
    static ToolKind kindOf(String taskType) {
        if (taskType == null) {
            return ToolKind.DETECTION;
        }
        String t = taskType.toLowerCase(Locale.ROOT);
        if (t.contains("classification") || t.contains("classify")) {
            return ToolKind.CLASSIFICATION;
        }
        return ToolKind.DETECTION;
    }

    /**
     * The tenant's stored Roboflow key, or null.
     *
     * <p>Resolved through the connection service, which is the one place that
     * knows the vault convention. Never returned to a caller, never cached.
     */
    private String credentialFor(String developerId) {
        if (developerId == null) {
            return null;
        }
        try {
            return connections.discoveryKeyFor(developerId, "roboflow").orElse(null);
        } catch (RuntimeException e) {
            // A vault that cannot answer means no discovery, not a broken Hub.
            log.debug("Could not read a Roboflow credential: {}", e.getMessage());
            return null;
        }
    }

    /** Drops cached searches for one tenant — used by the console's Refresh. */
    @Override
    public void invalidate(String developerId) {
        String prefix = developerId + "|";
        cache.keySet().removeIf(k -> developerId == null || k.startsWith(prefix));
        recent.keySet().removeIf(k -> developerId == null || k.startsWith(prefix));
    }
}
