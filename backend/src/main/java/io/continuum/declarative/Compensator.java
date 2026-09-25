package io.continuum.declarative;

import io.continuum.core.workflow.ActivityFailedException;
import io.continuum.core.workflow.ActivityOptions;
import io.continuum.core.workflow.WorkflowContext;
import io.continuum.saga.SagaPlan;
import io.continuum.saga.SagaRecordActivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a saga rollback from inside a workflow: undo what completed, newest
 * first, then write down what could not be undone.
 *
 * <p>Shared by the two places a rollback happens — a declarative run whose step
 * failed, and the rollback run started when someone cancels one — so both undo
 * the same way and both report to the same saga log.
 *
 * <p>Runs entirely through {@code executeActivity}, so each compensation is
 * durable, retried and replayed exactly like a forward step: a crash halfway
 * through a rollback resumes the rollback rather than restarting it. A
 * compensation that itself fails does not stop the others; it is reported as
 * stranded, which is the whole point of the report.
 */
final class Compensator {

    private Compensator() {
    }

    /**
     * @param ctx         the workflow doing the undoing
     * @param owner       the run whose steps are undone. Its id is what the
     *                    compensations carry and what their idempotency keys are
     *                    built from, so a receiver deduplicates a rollback run's
     *                    call against one the original run may already have made.
     * @param extraStranded steps known to be beyond undoing before we start
     */
    static void run(WorkflowContext ctx, String owner, String developerId, String definition,
                    WorkflowSpec spec, Map<String, Object> scope, List<String> completed,
                    String failedAt, String reason, List<String> extraStranded) {
        SagaPlan.Plan plan = SagaPlan.forFailure(spec, completed, failedAt);
        if (plan.compensations().isEmpty() && plan.uncompensated().isEmpty() && extraStranded.isEmpty()) {
            return;
        }

        List<String> undone = new ArrayList<>();
        List<String> stranded = new ArrayList<>(plan.uncompensated());
        stranded.addAll(extraStranded);
        for (SagaPlan.Compensation c : plan.compensations()) {
            WorkflowSpec.Call call = c.call();
            Map<String, String> headers = new LinkedHashMap<>();
            call.getHeaders().forEach((k, v) ->
                    headers.put(k, String.valueOf(Templates.resolve(v, scope))));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("compensating", c.stepId());
            body.put("workflowId", owner);
            body.put("reason", reason);
            Object authored = Templates.resolve(call.getBody(), scope);
            if (authored instanceof Map<?, ?> m) {
                m.forEach((k, v) -> body.put(String.valueOf(k), v));
            }
            try {
                ctx.executeActivity(HttpStepActivity.TYPE,
                        new HttpStepActivity.Input(
                                String.valueOf(Templates.resolve(call.getUrl(), scope)),
                                call.getMethod(), headers, body, 30,
                                owner + ":compensate:" + c.stepId()),
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
                new SagaRecordActivity.Input(developerId, owner, definition,
                        failedAt, undone, stranded, complete, summary),
                ActivityOptions.defaults().maxAttempts(3).timeoutSeconds(20),
                Map.class);
    }
}
