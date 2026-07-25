package io.continuum.declarative;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.core.workflow.ActivityOptions;
import io.continuum.core.workflow.Workflow;
import io.continuum.core.workflow.WorkflowContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a customer-authored {@link WorkflowSpec}.
 *
 * <p>One registered workflow type interprets every definition, which is what
 * makes durable execution available without compiling anything into the server.
 * Everything the engine already provides applies unchanged: a crash mid-run
 * resumes from the last completed step, results are replayed rather than
 * re-executed, and divergence healing still applies.
 *
 * <p>Determinism comes from three rules. The spec is pinned into the run's input
 * at start, so editing the definition afterwards cannot change how an in-flight
 * run replays. Layering and ordering are pure functions of that spec. And all
 * I/O happens inside {@link HttpStepActivity}, whose results are recorded.
 */
@Component
public class DeclarativeWorkflow implements Workflow {

    public static final String TYPE = "Declarative";

    private final ObjectMapper mapper;

    public DeclarativeWorkflow(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(WorkflowContext ctx) {
        Run run = ctx.input(Run.class);
        WorkflowSpec spec = mapper.convertValue(run.spec(), WorkflowSpec.class);

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("input", run.input() == null ? Map.of() : run.input());
        Map<String, Object> stepResults = new LinkedHashMap<>();
        scope.put("steps", stepResults);

        List<String> skipped = new ArrayList<>();

        for (List<WorkflowSpec.Step> layer : spec.topologicalLayers()) {
            // A guard is evaluated against values already recorded in history, so
            // it produces the same answer on every replay. Steps whose guard is
            // false are not scheduled at all.
            List<WorkflowSpec.Step> live = new ArrayList<>();
            for (WorkflowSpec.Step s : layer) {
                boolean guardPasses = s.getCondition() == null || s.getCondition().isBlank()
                        || Conditions.evaluate(s.getCondition(), scope);
                if (guardPasses) {
                    live.add(s);
                } else {
                    skipped.add(s.getId());
                    // Recorded so later references resolve rather than dangling.
                    stepResults.put(s.getId(), Map.of("skipped", true));
                }
            }
            if (live.isEmpty()) {
                continue;
            }

            // Independent steps in the same layer are scheduled together; the
            // engine's parallel barrier keeps replay semantics intact.
            List<WorkflowContext.ParallelCall> calls = new ArrayList<>();
            for (WorkflowSpec.Step s : live) {
                calls.add(s.getType() == WorkflowSpec.Kind.WAIT
                        ? waitCall(s)
                        : new WorkflowContext.ParallelCall(HttpStepActivity.TYPE, toInput(ctx, s, scope),
                                ActivityOptions.defaults()
                                        .maxAttempts(Math.max(1, s.getRetries() + 1))
                                        .timeoutSeconds(s.getTimeoutSeconds())));
            }

            List<Map> results = ctx.executeActivitiesParallel(calls, Map.class);
            for (int i = 0; i < live.size(); i++) {
                Map<String, Object> r = (Map<String, Object>) results.get(i);
                // A step's body is what later steps reference, so ${steps.x.field}
                // reads naturally instead of ${steps.x.body.field}.
                Object body = r == null ? null : r.get("body");
                stepResults.put(live.get(i).getId(), body instanceof Map ? body : wrap(r));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("definition", run.definition());
        out.put("version", run.version());
        out.put("steps", stepResults);
        out.put("skipped", skipped);

        // Push the result instead of making the caller poll. Delivered as a step,
        // so it retries and carries an idempotency key like any other call.
        if (spec.getOnComplete() != null) {
            Map<String, Object> payload = new LinkedHashMap<>(out);
            payload.put("workflowId", ctx.workflowId());
            payload.put("status", "COMPLETED");
            WorkflowSpec.Call cb = spec.getOnComplete();
            Map<String, String> headers = new LinkedHashMap<>();
            cb.getHeaders().forEach((k, v) -> headers.put(k, String.valueOf(Templates.resolve(v, scope))));
            Map<String, Object> delivery = ctx.executeActivity(HttpStepActivity.TYPE,
                    new HttpStepActivity.Input(String.valueOf(Templates.resolve(cb.getUrl(), scope)),
                            cb.getMethod(), headers, payload, 30, ctx.workflowId() + ":onComplete"),
                    ActivityOptions.defaults().maxAttempts(5).timeoutSeconds(30),
                    Map.class);
            out.put("callbackStatus", delivery == null ? null : delivery.get("status"));
        }
        return out;
    }

    /**
     * A durable timer. The task is simply not claimable until it is due, so the
     * wait occupies no worker and survives a restart like any other step.
     */
    private WorkflowContext.ParallelCall waitCall(WorkflowSpec.Step s) {
        int seconds = s.getWaitSeconds() == null ? 1 : s.getWaitSeconds();
        return new WorkflowContext.ParallelCall(
                WaitStepActivity.TYPE,
                new WaitStepActivity.Input(s.getId(), seconds),
                ActivityOptions.defaults().maxAttempts(1).timeoutSeconds(30).delaySeconds(seconds));
    }

    private HttpStepActivity.Input toInput(WorkflowContext ctx, WorkflowSpec.Step s, Map<String, Object> scope) {
        WorkflowSpec.Call call = s.getCall();
        Object url = Templates.resolve(call.getUrl(), scope);
        Object body = Templates.resolve(call.getBody(), scope);

        Map<String, String> headers = new LinkedHashMap<>();
        call.getHeaders().forEach((k, v) -> headers.put(k, String.valueOf(Templates.resolve(v, scope))));

        // Stable across retries and replays, so the receiving service can dedupe.
        String idem = ctx.workflowId() + ":" + s.getId();

        return new HttpStepActivity.Input(String.valueOf(url), call.getMethod(), headers, body,
                s.getTimeoutSeconds(), idem);
    }

    private static Map<String, Object> wrap(Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", v);
        return m;
    }

    /**
     * What a run is started with. The spec is embedded rather than referenced so
     * the execution is immune to later edits of the definition.
     */
    public record Run(String definition, int version, Object spec, Map<String, Object> input) {
    }
}
