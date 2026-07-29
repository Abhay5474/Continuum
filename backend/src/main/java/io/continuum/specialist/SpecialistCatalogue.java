package io.continuum.specialist;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Hub: search for something to plug in, and add it in one step.
 *
 * <p>Fans out over every {@link CatalogueSource}: the shipped templates, plus
 * any live provider directory that can answer for this tenant. A source that is
 * unavailable does not fail the search — it comes back in the source list with
 * the reason, so an empty result set can be told apart from a directory that is
 * simply not reachable.
 *
 * <p>The install is the part that earns the feature. Adding a specialist by hand
 * means knowing the provider, the base URL, the auth style, the model path, a
 * sensible threshold and a timeout, then remembering to probe it. From the Hub
 * it is: pick one, supply the parts only you can know, and Continuum creates the
 * connection, creates the specialist and <b>probes it immediately</b> — so what
 * you get back is either a working integration or the exact reason it isn't.
 */
@Service
public class SpecialistCatalogue {

    private final List<CatalogueSource> sources;
    private final CuratedCatalogue curated;
    private final SpecialistConnectionService connections;
    private final SpecialistService specialists;

    public SpecialistCatalogue(List<CatalogueSource> sources, CuratedCatalogue curated,
                               SpecialistConnectionService connections,
                               SpecialistService specialists) {
        this.sources = sources;
        this.curated = curated;
        this.connections = connections;
        this.specialists = specialists;
    }

    /**
     * Search results, plus the state of every source.
     *
     * <p>The source list is returned even when it is boring, because an empty
     * result set means nothing without it. "No match for wound" and "the only
     * directory that would have known is unreachable" are different answers, and
     * a developer who cannot tell them apart will go and check their spelling
     * when they should be checking their network.
     */
    public Map<String, Object> search(String query, int limit) {
        List<Map<String, Object>> entries = new ArrayList<>();
        List<Map<String, Object>> state = new ArrayList<>();

        for (CatalogueSource s : sources) {
            boolean up = false;
            String reason = null;
            int found = 0;
            try {
                up = s.available();
                if (up) {
                    for (CatalogueEntry e : s.search(query, limit)) {
                        entries.add(e.describe());
                        found++;
                    }
                } else {
                    reason = s.unavailableReason();
                }
            } catch (RuntimeException e) {
                // A source that misbehaves must not take the Hub down with it.
                up = false;
                reason = "This source failed while answering the search.";
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("available", up);
            // LIVE means "these models exist right now"; TEMPLATE means "this is
            // a shape you still have to point at something". Showing a developer
            // one badge is the difference between trusting a result and having
            // to work out where it came from.
            m.put("type", s.live() ? "LIVE" : "TEMPLATE");
            m.put("live", s.live());
            m.put("results", found);
            m.put("reason", reason);
            state.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query == null ? "" : query);
        out.put("entries", entries);
        out.put("sources", state);
        out.put("allSourcesAvailable", state.stream().allMatch(m -> Boolean.TRUE.equals(m.get("available"))));
        return out;
    }

    /**
     * Drops every live source's cache for this tenant and searches again.
     *
     * <p>Exists because a cache a developer cannot clear is a bug report: they
     * publish a model, search for it, and are told it does not exist for the
     * next ten minutes.
     */
    public Map<String, Object> refresh(String developerId, String query, int limit) {
        for (CatalogueSource s : sources) {
            try {
                s.invalidate(developerId);
            } catch (RuntimeException ignored) {
                // Refresh is best-effort; one uncooperative source must not stop
                // the others being cleared.
            }
        }
        return search(query, limit);
    }

    /** Everything, for the empty-query case where the console shows the shelf. */
    public Map<String, Object> browse(int limit) {
        return search("", limit);
    }

    /**
     * Creates the connection and the specialist from a template, then probes.
     *
     * @param connectionId reuse an existing connection instead of making one;
     *                     the provider must match, or the credential would be
     *                     presented in a style the target does not expect
     * @param secret       required when creating a connection, never when reusing
     */
    @Transactional
    public Map<String, Object> install(String developerId, String entryId, String name,
                                       String baseUrl, String modelPath, Double minConfidence,
                                       Long connectionId, String secret) {
        CatalogueEntry entry = resolve(entryId);
        if (entry == null) {
            throw new SpecialistConnectionService.InvalidConnectionException(
                    "No catalogue entry called '" + entryId + "'. If it came from a live "
                            + "directory the result may have aged out — search again and install "
                            + "from the fresh result.");
        }

        String path = blankToNull(modelPath) == null ? entry.modelPath() : modelPath.strip();
        if (entry.needs().contains("modelPath") && blankToNull(path) == null) {
            throw new SpecialistConnectionService.InvalidConnectionException(
                    "This one needs a model path — it is the part only you know.");
        }

        Map<String, Object> connection;
        if (connectionId != null) {
            // Reusing: check the provider matches before anything is created.
            // A Roboflow key presented as a bearer token to a custom endpoint
            // fails in a way that looks like a broken model.
            var existing = connections.require(developerId, connectionId);
            if (!existing.getProvider().equals(entry.provider())) {
                throw new SpecialistConnectionService.InvalidConnectionException(
                        "That connection is for " + existing.getProvider() + ", but this entry "
                                + "needs a " + entry.provider() + " one — the credential would be "
                                + "presented in the wrong place.");
            }
            connection = connections.describe(existing);
        } else {
            if (blankToNull(secret) == null) {
                throw new SpecialistConnectionService.InvalidConnectionException(
                        "A credential is needed, or pick an existing connection to reuse.");
            }
            String base = blankToNull(baseUrl) == null ? entry.baseUrl() : baseUrl.strip();
            if (blankToNull(base) == null) {
                throw new SpecialistConnectionService.InvalidConnectionException(
                        "This one runs on your own infrastructure, so it needs a base URL.");
            }
            connection = connections.create(developerId, name + " endpoint", entry.provider(),
                    base, null, null, secret);
        }

        Long cid = ((Number) connection.get("id")).longValue();
        Map<String, Object> specialist = specialists.create(developerId, cid, name, path,
                entry.inputKind(), entry.toolKind(),
                minConfidence == null ? entry.suggestedConfidence() : minConfidence,
                null);

        // Probed here rather than left to the developer. An unprobed specialist
        // cannot go into a pipeline anyway, so deferring it only means finding
        // out later that the credential was wrong.
        Long sid = ((Number) specialist.get("id")).longValue();
        Map<String, Object> probed;
        try {
            probed = specialists.probe(developerId, sid, null);
        } catch (RuntimeException e) {
            // The specialist exists and is DRAFT; the probe result carries the
            // reason. Rolling the whole install back would throw away correct
            // configuration over a temporarily unreachable endpoint.
            probed = specialist;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entry", entry.describe());
        out.put("connection", connection);
        out.put("specialist", probed);
        return out;
    }

    /**
     * An entry by id, from whichever source has it.
     *
     * <p>The curated shelf is asked first: it is local, it always answers, and
     * its ids are stable. Live sources are asked afterwards and may legitimately
     * have forgotten — a discovered result is only installable while it is still
     * in hand, which is why this returns null rather than rebuilding one.
     */
    public CatalogueEntry resolve(String entryId) {
        CatalogueEntry entry = curated.byId(entryId);
        if (entry != null) {
            return entry;
        }
        for (CatalogueSource s : sources) {
            try {
                CatalogueEntry e = s.byId(entryId);
                if (e != null) {
                    return e;
                }
            } catch (RuntimeException ignored) {
                // A source that cannot answer is not one that gets to fail the
                // lookup for the sources after it.
            }
        }
        return null;
    }

    /** Which of a developer's connections a given entry could reuse. */
    public List<Map<String, Object>> reusable(String developerId, String entryId) {
        CatalogueEntry entry = resolve(entryId);
        if (entry == null) {
            return List.of();
        }
        return connections.list(developerId).stream()
                .filter(c -> entry.provider().equals(c.get("provider")))
                .sorted(Comparator.comparing(c -> String.valueOf(c.get("name"))))
                .toList();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
