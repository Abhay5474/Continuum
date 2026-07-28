package io.continuum.declarative;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A customer-authored workflow: a graph of HTTP steps.
 *
 * <p>The engine already provides durability, retries, exactly-once side effects
 * and deterministic replay; what was missing was a way to express your own
 * orchestration without compiling a class into the server. A spec is that —
 * declarative, versioned, and interpreted.
 *
 * <pre>
 * {
 *   "steps": [
 *     { "id": "reserve",
 *       "call": { "method": "POST", "url": "https://api.acme.com/reserve",
 *                 "body": { "sku": "${input.sku}" } },
 *       "retries": 5, "timeoutSeconds": 30 },
 *     { "id": "charge", "dependsOn": ["reserve"],
 *       "call": { "method": "POST", "url": "https://api.acme.com/charge",
 *                 "body": { "hold": "${steps.reserve.holdId}" } } }
 *   ]
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowSpec {

    private String description;
    private List<Step> steps = new ArrayList<>();
    private Call onComplete;

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<Step> getSteps() { return steps; }
    public void setSteps(List<Step> steps) { this.steps = steps == null ? new ArrayList<>() : steps; }

    /**
     * Optional callback fired once every step has finished, so a caller does not
     * have to poll for the result. It is delivered as an ordinary durable step,
     * which means it inherits retries and the idempotency key like anything else.
     */
    public Call getOnComplete() { return onComplete; }
    public void setOnComplete(Call onComplete) { this.onComplete = onComplete; }

    /** What a step does. */
    public enum Kind { HTTP, WAIT }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Step {
        private String id;
        private Kind type = Kind.HTTP;
        private Call call;
        private Integer waitSeconds;
        private String condition;
        private List<String> dependsOn = new ArrayList<>();
        private int retries = 3;
        private int timeoutSeconds = 30;
        private Call compensate;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public Kind getType() { return type; }
        public void setType(Kind type) { this.type = type == null ? Kind.HTTP : type; }
        public Integer getWaitSeconds() { return waitSeconds; }
        public void setWaitSeconds(Integer w) { this.waitSeconds = w; }
        /** Optional guard; the step is skipped when it evaluates false. */
        public String getCondition() { return condition; }
        public void setCondition(String condition) { this.condition = condition; }
        public Call getCall() { return call; }
        public void setCall(Call call) { this.call = call; }
        public List<String> getDependsOn() { return dependsOn; }
        public void setDependsOn(List<String> d) { this.dependsOn = d == null ? new ArrayList<>() : d; }
        public int getRetries() { return retries; }
        public void setRetries(int retries) { this.retries = retries; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int t) { this.timeoutSeconds = t; }

        /**
         * How to undo this step if a later one fails.
         *
         * <p>A durable engine guarantees each step runs exactly once; it cannot
         * guarantee the <em>set</em> of them is all-or-nothing, because the
         * calls go to systems that have never heard of this transaction. You
         * cannot roll back a charge at a payment provider — you can only issue a
         * refund. That is a saga (Garcia-Molina &amp; Salem, 1987): forward
         * steps paired with compensating ones, run in reverse on failure.
         *
         * <p>Optional per step. A step with nothing to undo — a read, a
         * notification already delivered — simply has none, and the plan says so
         * rather than pretending the rollback was complete.
         */
        public Call getCompensate() { return compensate; }
        public void setCompensate(Call c) { this.compensate = c; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Call {
        private String method = "POST";
        private String url;
        private Map<String, String> headers = new HashMap<>();
        private Object body;

        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> h) { this.headers = h == null ? new HashMap<>() : h; }
        public Object getBody() { return body; }
        public void setBody(Object body) { this.body = body; }
    }

    /** Raised when a spec could not be accepted; the message is shown to the author. */
    public static class InvalidSpecException extends IllegalArgumentException {
        public InvalidSpecException(String message) {
            super(message);
        }
    }

    /**
     * Rejects a spec the engine could not execute deterministically or safely.
     * Validation happens at publish time so failures surface when you author the
     * workflow, not halfway through a production run.
     */
    public void validate() {
        if (steps.isEmpty()) {
            throw new InvalidSpecException("A workflow needs at least one step.");
        }
        if (steps.size() > 100) {
            throw new InvalidSpecException("A workflow is limited to 100 steps.");
        }
        Set<String> ids = new HashSet<>();
        for (Step s : steps) {
            if (s.getId() == null || !s.getId().matches("[A-Za-z0-9_-]{1,64}")) {
                throw new InvalidSpecException(
                        "Step id '" + s.getId() + "' must be 1-64 chars of letters, digits, _ or -.");
            }
            if (!ids.add(s.getId())) {
                throw new InvalidSpecException("Duplicate step id: " + s.getId());
            }
            if (s.getType() == Kind.WAIT) {
                if (s.getWaitSeconds() == null || s.getWaitSeconds() < 1 || s.getWaitSeconds() > 86_400) {
                    throw new InvalidSpecException(
                            "Step '" + s.getId() + "': waitSeconds must be between 1 and 86400.");
                }
            } else if (s.getCall() == null || s.getCall().getUrl() == null || s.getCall().getUrl().isBlank()) {
                throw new InvalidSpecException("Step '" + s.getId() + "' needs a call.url.");
            }
            if (s.getCondition() != null && !s.getCondition().isBlank()) {
                // Fail at publish time rather than mid-run on an unparseable guard.
                Conditions.parse(s.getCondition());
            }
            if (s.getRetries() < 0 || s.getRetries() > 25) {
                throw new InvalidSpecException("Step '" + s.getId() + "': retries must be between 0 and 25.");
            }
            if (s.getTimeoutSeconds() < 1 || s.getTimeoutSeconds() > 300) {
                throw new InvalidSpecException(
                        "Step '" + s.getId() + "': timeoutSeconds must be between 1 and 300.");
            }
        }
        for (Step s : steps) {
            for (String dep : s.getDependsOn()) {
                if (!ids.contains(dep)) {
                    throw new InvalidSpecException("Step '" + s.getId() + "' depends on unknown step '" + dep + "'.");
                }
                if (dep.equals(s.getId())) {
                    throw new InvalidSpecException("Step '" + s.getId() + "' depends on itself.");
                }
            }
        }
        if (onComplete != null && (onComplete.getUrl() == null || onComplete.getUrl().isBlank())) {
            throw new InvalidSpecException("onComplete needs a url.");
        }
        // A cycle would never terminate, so it is rejected rather than discovered
        // at run time.
        topologicalLayers();
    }

    /**
     * Steps grouped into dependency layers. Everything in one layer is
     * independent, so the engine can schedule that layer in parallel — and the
     * layering is a pure function of the spec, which keeps replay deterministic.
     */
    public List<List<Step>> topologicalLayers() {
        Map<String, Step> byId = new HashMap<>();
        steps.forEach(s -> byId.put(s.getId(), s));
        Set<String> done = new HashSet<>();
        List<List<Step>> layers = new ArrayList<>();

        while (done.size() < steps.size()) {
            List<Step> layer = new ArrayList<>();
            for (Step s : steps) {
                if (done.contains(s.getId())) {
                    continue;
                }
                if (done.containsAll(s.getDependsOn())) {
                    layer.add(s);
                }
            }
            if (layer.isEmpty()) {
                throw new InvalidSpecException("Steps form a dependency cycle.");
            }
            // Stable order within a layer so replay reproduces the same sequence.
            layer.sort((a, b) -> a.getId().compareTo(b.getId()));
            layer.forEach(s -> done.add(s.getId()));
            layers.add(layer);
        }
        return layers;
    }
}
