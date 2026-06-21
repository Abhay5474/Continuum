package io.continuum.workflows;

import io.continuum.activities.EchoActivity;
import io.continuum.core.workflow.ActivityOptions;
import io.continuum.core.workflow.Workflow;
import io.continuum.core.workflow.WorkflowContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 0 — pure durability, no AI.
 *
 * Three sequential steps plus a captured timestamp. The point of this workflow
 * is the demo: start it, kill a worker mid-step, restart, and watch it resume
 * from exactly where it left off — without repeating completed steps and with
 * the same recorded timestamp on replay.
 */
@Component
public class DurableDemoWorkflow implements Workflow {

    public static final String TYPE = "DurableDemo";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object execute(WorkflowContext ctx) {
        Input in = ctx.input(Input.class);
        ActivityOptions opts = ActivityOptions.defaults().maxAttempts(5).timeoutSeconds(15);

        // Captured once; identical on every replay.
        long startedAtMillis = ctx.now().toEpochMilli();

        EchoActivity.Output a = ctx.executeActivity(EchoActivity.TYPE,
                new EchoActivity.Input("step-1-" + in.name()), opts, EchoActivity.Output.class);
        EchoActivity.Output b = ctx.executeActivity(EchoActivity.TYPE,
                new EchoActivity.Input("step-2-" + in.name()), opts, EchoActivity.Output.class);
        EchoActivity.Output c = ctx.executeActivity(EchoActivity.TYPE,
                new EchoActivity.Input("step-3-" + in.name()), opts, EchoActivity.Output.class);

        return new Output(in.name(), startedAtMillis, List.of(a.message(), b.message(), c.message()));
    }

    public record Input(String name) {
    }

    public record Output(String name, long startedAtMillis, List<String> steps) {
    }
}
