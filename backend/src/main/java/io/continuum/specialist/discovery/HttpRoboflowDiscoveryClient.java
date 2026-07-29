package io.continuum.specialist.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.net.GuardedHttpSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Roboflow directory search over HTTP.
 *
 * <p><b>LIVE_UNVERIFIED.</b> Written against Roboflow's documented search
 * contract; never run against the real service, because this deployment refuses
 * every model host at CONNECT. Nothing here is fabricated — no invented response
 * is baked in, no fixture is presented as a live result — but the field mapping
 * has not been confirmed against a real body, and the class says so rather than
 * implying otherwise.
 *
 * <p>Two consequences follow from that, and they shape the whole design.
 *
 * <p><b>Parsing is tolerant.</b> Each value is looked for under several
 * plausible names, and every optional field may be absent. A body shaped
 * slightly differently from the documentation yields a partial entry the console
 * can still show, rather than an exception or an empty list. The raw body is
 * always returned alongside, so a developer whose search comes back thin can see
 * exactly what the provider said.
 *
 * <p><b>An unusable result is reported, not repaired.</b> {@code RoboflowProvider}
 * needs {@code project/version} to build a call. A directory entry that does not
 * carry one is surfaced as needing the developer to supply the model path — it
 * is never guessed at, because a guessed path produces a one-click install that
 * 404s, which is worse than asking.
 */
@Component
public class HttpRoboflowDiscoveryClient implements RoboflowDiscoveryClient {

    private static final Logger log = LoggerFactory.getLogger(HttpRoboflowDiscoveryClient.class);

    /** Field names the same value plausibly arrives under. Order is preference. */
    private static final List<String> LIST_KEYS =
            List.of("results", "projects", "models", "data", "items", "hits");
    private static final List<String> NAME_KEYS =
            List.of("name", "displayName", "title", "project", "id");
    private static final List<String> DESC_KEYS =
            List.of("description", "annotation", "summary", "notes");
    private static final List<String> WORKSPACE_KEYS =
            List.of("workspace", "workspaceId", "owner", "universe_workspace");
    private static final List<String> PROJECT_KEYS =
            List.of("project", "projectId", "id", "slug", "url");
    private static final List<String> TASK_KEYS =
            List.of("type", "taskType", "task", "projectType", "modelType");

    private final GuardedHttpSender sender;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final int timeoutSeconds;

    public HttpRoboflowDiscoveryClient(
            GuardedHttpSender sender, ObjectMapper mapper,
            @Value("${continuum.discovery.roboflow.base-url:https://api.roboflow.com}") String baseUrl,
            @Value("${continuum.discovery.roboflow.timeout-seconds:10}") int timeoutSeconds) {
        this.sender = sender;
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Configurable so a deployment that proxies the provider, or a test that
     * points at a local fixture server, needs no code change.
     */
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public boolean reachable() {
        // Deliberately not a live network probe on every Hub render — that would
        // add a round trip to a page load and, when the host is refused, a
        // timeout. Reachability is learned from the last real search instead.
        return lastReachable;
    }

    private volatile boolean lastReachable = true;

    @Override
    public SearchResponse search(String apiKey, String query, int limit) {
        if (apiKey == null || apiKey.isBlank()) {
            return SearchResponse.of(Status.NO_CREDENTIAL,
                    "Roboflow search needs your own Roboflow API key. Add a Roboflow connection "
                            + "and the Hub will use it for discovery as well as for calls.");
        }
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            // An empty query against a directory of hundreds of thousands of
            // projects is not a search, and asking for one is impolite to the
            // provider as well as useless to the developer.
            return SearchResponse.of(Status.OK, "Type something to search Roboflow.");
        }

        String url = baseUrl + "/search?api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", q);
        body.put("limit", Math.max(1, Math.min(limit, 50)));

        GuardedHttpSender.Result res;
        try {
            res = sender.send(url, "POST",
                    Map.of("Content-Type", "application/json"),
                    mapper.writeValueAsString(body), timeoutSeconds);
            lastReachable = true;
        } catch (Exception e) {
            lastReachable = false;
            // The exception can name the host and the proxy. The developer is
            // told the directory is unreachable; they are not handed the
            // deployment's network topology.
            log.debug("Roboflow discovery unreachable: {}", e.toString());
            return SearchResponse.of(Status.UNREACHABLE,
                    "Roboflow's directory could not be reached from this deployment. Continuum's "
                            + "own catalogue is still searchable, and any Roboflow model you "
                            + "already know the path of can still be added by hand.");
        }

        if (res.status() == 401 || res.status() == 403) {
            return SearchResponse.of(Status.UNAUTHORISED,
                    "Roboflow refused the stored API key. Rotate the credential on the connection "
                            + "and try again.");
        }
        if (res.status() >= 400) {
            return new SearchResponse(Status.PROVIDER_ERROR, List.of(),
                    "Roboflow returned HTTP " + res.status() + " for this search.", res.body());
        }

        Object parsed;
        try {
            parsed = mapper.readValue(res.body(), Object.class);
        } catch (Exception e) {
            return new SearchResponse(Status.UNREADABLE, List.of(),
                    "Roboflow answered with something that is not JSON. The body is shown so you "
                            + "can see what arrived.", res.body());
        }

        List<Model> models = map(parsed);
        return new SearchResponse(Status.OK, models,
                models.isEmpty()
                        ? "Roboflow returned no models matching that. The raw response is shown "
                                + "in case the shape is not what Continuum expected."
                        : models.size() + " model" + (models.size() == 1 ? "" : "s") + " found.",
                res.body());
    }

    /**
     * Maps a provider body onto models, tolerantly.
     *
     * <p>Package-private so the mapping can be tested against fixtures without a
     * network — which is the only way it can be tested here at all.
     */
    List<Model> map(Object parsed) {
        List<Model> out = new ArrayList<>();
        List<?> rows = rowsOf(parsed);
        for (Object o : rows) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> row = stringKeyed(m);
            String name = firstString(row, NAME_KEYS);
            if (name == null) {
                // Nothing to show a developer. Skipped rather than displayed as
                // an entry called "null".
                continue;
            }
            String workspace = firstString(row, WORKSPACE_KEYS);
            String project = slug(firstString(row, PROJECT_KEYS));
            String version = versionOf(row);
            String task = firstString(row, TASK_KEYS);

            out.add(new Model(
                    "roboflow:" + (workspace == null ? "" : workspace + "/")
                            + (project == null ? name : project)
                            + (version == null ? "" : "/" + version),
                    name,
                    firstString(row, DESC_KEYS),
                    workspace, project, version, task,
                    // Only when both halves are known. RoboflowProvider builds
                    // "{base}/{project}/{version}", and half of that is a 404.
                    project != null && version != null ? project + "/" + version : null,
                    row));
        }
        return out;
    }

    // --- tolerant readers ----------------------------------------------------

    private static List<?> rowsOf(Object parsed) {
        if (parsed instanceof List<?> l) {
            return l;
        }
        if (parsed instanceof Map<?, ?> m) {
            Map<String, Object> body = stringKeyed(m);
            for (String k : LIST_KEYS) {
                if (body.get(k) instanceof List<?> l) {
                    return l;
                }
            }
        }
        return List.of();
    }

    /**
     * A field, matched leniently on the key.
     *
     * <p>Directories differ on {@code display_name} versus {@code displayName},
     * and it is not worth returning nothing over. Both the stored keys and the
     * requested one are reduced to lower case without separators before they are
     * compared, so either spelling finds either.
     */
    private static Object lookup(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v : row.get(normalise(key));
    }

    private static String normalise(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    /** The newest version number, under any of the shapes a directory uses. */
    private static String versionOf(Map<String, Object> row) {
        for (String k : List.of("version", "latestVersion", "versions")) {
            Object v = lookup(row, k);
            if (v instanceof Number n) {
                return String.valueOf(n.intValue());
            }
            if (v instanceof String s && s.matches("\\d+")) {
                return s;
            }
            if (v instanceof List<?> l && !l.isEmpty()) {
                // A list of versions: the last one is the newest by convention,
                // and a version object carries its own number.
                Object last = l.get(l.size() - 1);
                if (last instanceof Number n) {
                    return String.valueOf(n.intValue());
                }
                if (last instanceof String s && s.matches("\\d+")) {
                    return s;
                }
                if (last instanceof Map<?, ?> vm) {
                    Object id = stringKeyed(vm).get("id");
                    if (id != null && id.toString().matches("\\d+")) {
                        return id.toString();
                    }
                }
            }
        }
        return null;
    }

    /** A project identifier from a name, id or URL, without inventing one. */
    private static String slug(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        if (s.startsWith("http")) {
            // ".../workspace/project" — the last non-empty segment is the project.
            String[] parts = s.split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                if (!parts[i].isBlank() && !parts[i].matches("\\d+")) {
                    return parts[i];
                }
            }
            return null;
        }
        return s.contains("/") ? s.substring(s.lastIndexOf('/') + 1) : s;
    }

    private static String firstString(Map<String, Object> row, List<String> keys) {
        for (String k : keys) {
            Object v = lookup(row, k);
            if (v instanceof String s && !s.isBlank()) {
                return s.strip();
            }
        }
        return null;
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null) {
                out.put(k.toString(), v);
            }
        });
        // A normalised alias for every key, so {@link #lookup} finds a value
        // whichever spelling the provider chose. Built here rather than at
        // lookup time so a row is walked once, not once per field.
        Map<String, Object> alias = new LinkedHashMap<>();
        out.forEach((k, v) -> alias.putIfAbsent(normalise(k), v));
        alias.forEach(out::putIfAbsent);
        return out;
    }
}
