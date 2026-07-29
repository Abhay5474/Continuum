package io.continuum.specialist.discovery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place that talks to Roboflow's directory over the network.
 *
 * <p>An interface rather than a class so the discovery <em>logic</em> — mapping
 * a provider's answer onto a Continuum catalogue entry — can be tested against
 * fixtures without a network, which matters because this deployment has none.
 * Every model host is refused at CONNECT here:
 *
 * <pre>
 *   universe.roboflow.com   000
 *   api.roboflow.com        000
 *   detect.roboflow.com     000
 * </pre>
 *
 * <p><b>Live status: unverified.</b> The implementation is written against
 * Roboflow's documented search contract and has never received a response from
 * the real service, because none could be requested. It is deliberately
 * <em>tolerant</em> in what it accepts — several plausible field names for the
 * same value, no assumption that any optional field is present — so that a
 * shape slightly different from the documentation degrades to a partial entry
 * rather than to an exception. What it will not do is invent data: a result it
 * cannot identify well enough to invoke is reported as needing the developer's
 * input, not filled in with a guess.
 */
public interface RoboflowDiscoveryClient {

    /** Why a search did not return results. Distinct causes, distinct advice. */
    enum Status {
        /** Results were returned, possibly zero of them. */
        OK,
        /** No Roboflow credential is stored for this tenant. */
        NO_CREDENTIAL,
        /** The host could not be reached at all. */
        UNREACHABLE,
        /** Reached, and refused the credential. */
        UNAUTHORISED,
        /** Reached, answered, and the answer could not be read. */
        UNREADABLE,
        /** Reached and returned an error status. */
        PROVIDER_ERROR
    }

    /**
     * One model as the provider described it.
     *
     * <p>{@code invocationPath} is the field that decides whether a result is
     * usable: {@code RoboflowProvider} needs {@code project/version} to build a
     * call, and a directory entry that cannot supply one is a search result the
     * developer must finish by hand rather than a one-click install.
     */
    record Model(String id, String name, String description, String workspace,
                 String project, String version, String taskType,
                 String invocationPath, Map<String, Object> raw) {

        /** Whether Continuum knows enough to call this model without asking. */
        public boolean invocable() {
            return invocationPath != null && !invocationPath.isBlank();
        }

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("description", description);
            m.put("workspace", workspace);
            m.put("project", project);
            m.put("version", version);
            m.put("taskType", taskType);
            m.put("invocationPath", invocationPath);
            m.put("invocable", invocable());
            return m;
        }
    }

    /**
     * @param status  why this is the answer it is
     * @param models  best matches first; empty unless {@code status == OK}
     * @param detail  written for the developer. Must never carry a credential,
     *                a host name or anything else about the deployment's network
     * @param rawBody the provider's own body, for the console's "what came back"
     *                pane. Null unless a body was actually received
     */
    record SearchResponse(Status status, List<Model> models, String detail, String rawBody) {

        public boolean ok() {
            return status == Status.OK;
        }

        public static SearchResponse of(Status status, String detail) {
            return new SearchResponse(status, List.of(), detail, null);
        }

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", status.name());
            m.put("detail", detail);
            m.put("models", models.stream().map(Model::describe).toList());
            return m;
        }
    }

    /**
     * Searches the provider's directory.
     *
     * <p>Must never throw. A network failure, a refused credential and an
     * unreadable body are all outcomes the Hub has to show differently, and an
     * exception collapses them into one.
     *
     * @param apiKey the tenant's own Roboflow key, already decrypted. Never
     *               logged, never echoed into {@code detail} or {@code rawBody}
     */
    SearchResponse search(String apiKey, String query, int limit);

    /** Whether this client could reach the provider at all, for the source list. */
    boolean reachable();
}
