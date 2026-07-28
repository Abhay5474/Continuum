package io.continuum.declarative;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.core.workflow.ActivityFailedException;
import io.continuum.core.workflow.ActivityOptions;
import io.continuum.core.workflow.Workflow;
import io.continuum.core.workflow.WorkflowContext;
import io.continuum.saga.SagaPlan;
import io.continuum.saga.SagaRecordActivity;
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

            List<Map> results;
            try {
                results = ctx.executeActivitiesParallel(calls, Map.class);
            } catch (ActivityFailedException failure) {
                // The forward path is over. Everything already in stepResults ran
                // against a system that has never heard of this transaction, so
                // undoing it is the workflow's job, not the engine's.
                compensate(ctx, run, spec, scope, stepResults, live, failure);
                throw failure;
            }
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
     * Undoes what completed, newest first, then writes down what it could not.
     *
     * <p>Runs entirely through {@link #executeActivity}, so each compensation is
     * durable, retried and replayed exactly like a forward step: a crash halfway
     * through a rollback resumes the rollback rather than restarting it.
     *
     * <p>A compensation that itself fails does not stop the others. The remaining
     * ones still need to run, and the failed one is reported as stranded — which
     * is the whole point of the report.
     */
    private void compensate(WorkflowContext ctx, Run run, WorkflowSpec spec,
                            Map<String, Object> scope, Map<String, Object> stepResults,
                            List<WorkflowSpec.Step> failedLayer, ActivityFailedException failure) {
        if (!run.compensate()) {
            return;
        }
        // Insertion order into stepResults is completion order. Guard-skipped
        // steps never ran, so there is nothing of them to undo.
        List<String> completed = new ArrayList<>();
        for (Map.Entry<String, Object> e : stepResults.entrySet()) {
            Object v = e.getValue();
            boolean skipped = v instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("skipped"));
            if (!skipped) {
                completed.add(e.getKey());
            }
        }
        String failedAt = failedLayer.stream().map(WorkflowSpec.Step::getId)
                .collect(java.util.stream.Collectors.joining(", "));

        SagaPlan.Plan plan = SagaPlan.forFailure(spec, completed, failedAt);
        if (plan.compensations().isEmpty() && plan.uncompensated().isEmpty()) {
            return;
        }

        List<String> undone = new ArrayList<>();
        List<String> stranded = new ArrayList<>(plan.uncompensated());
        for (SagaPlan.Compensation c : plan.compensations()) {
            WorkflowSpec.Call call = c.call();
            Map<String, String> headers = new LinkedHashMap<>();
            call.getHeaders().forEach((k, v) ->
                    headers.put(k, String.valueOf(Templates.resolve(v, scope))));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("compensating", c.stepId());
            body.put("workflowId", ctx.workflowId());
            body.put("reason", failure.getMessage());
            Object authored = Templates.resolve(call.getBody(), scope);
            if (authored instanceof Map<?, ?> m) {
                m.forEach((k, v) -> body.put(String.valueOf(k), v));
            }
            try {
                ctx.executeActivity(HttpStepActivity.TYPE,
                        new HttpStepActivity.Input(
                                String.valueOf(Templates.resolve(call.getUrl(), scope)),
                                call.getMethod(), headers, body, 30,
                                ctx.workflowId() + ":compensate:" + c.stepId()),
                        ActivityOptions.defaults().maxAttempts(3).timeoutSeconds(30),
                        Map.class);
                undone.add(c.stepId());
            } catch (ActivityFailedException e) {
                // Its effect is still out there and now nothing else will remove
                // it. That belongs in the stranded list, not in a log line.
                stranded.add(c.stepId() + " (compensation failed)");
            }
        }

        boolean complete = stranded.isEmpty();
        String summary = complete
                ? undone.size() + " step" + (undone.size() == 1 ? "" : "s") + " rolled back."
                : undone.size() + " rolled back; " + stranded.size()
                        + " could not be and their effects remain.";
        ctx.executeActivity(SagaRecordActivity.TYPE,
                new SagaRecordActivity.Input(run.developerId(), ctx.workflowId(), run.definition(),
                        failedAt, undone, stranded, complete, summary),
                ActivityOptions.defaults().maxAttempts(3).timeoutSeconds(20),
                Map.class);
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
    /**
     * @param compensate whether saga rollback is on, pinned at start so toggling
     *                   the setting cannot change how an in-flight run replays.
     *                   Runs started before this field existed deserialize it as
     *                   {@code false}, which is what they actually ran with.
     */
    public record Run(String definition, int version, Object spec, Map<String, Object> input,
                      boolean compensate, String developerId) {
    }
}
