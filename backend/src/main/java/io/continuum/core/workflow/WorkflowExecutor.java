package io.continuum.core.workflow;

import io.continuum.common.Json;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Runs a single deterministic decision by replaying workflow code against a
 * reconstructed {@link WorkflowContext}.
 *
 * This class is intentionally free of any persistence or I/O so that the core
 * determinism guarantee can be unit-tested in isolation: given the same history,
 * the same {@link Commands.Decision} is always produced.
 */
@Component
public class WorkflowExecutor {

    private final Json json;

    public WorkflowExecutor(Json json) {
        this.json = json;
    }

    public Commands.Decision runDecision(Workflow workflow,
                                         String workflowId,
                                         String inputJson,
                                         Map<Long, String> completedResults,
                                         Map<Long, String> failedActivities,
                                         Map<Long, String> recordedSideEffects,
                                         Set<Long> scheduledPending) {
        return runDecision(workflow, workflowId, inputJson, completedResults,
                failedActivities, recordedSideEffects, scheduledPending, ReplayAligner.IDENTITY);
    }

    public Commands.Decision runDecision(Workflow workflow,
                                         String workflowId,
                                         String inputJson,
                                         Map<Long, String> completedResults,
                                         Map<Long, String> failedActivities,
                                         Map<Long, String> recordedSideEffects,
                                         Set<Long> scheduledPending,
                                         ReplayAligner aligner) {

        WorkflowContext ctx = new WorkflowContext(
                workflowId, inputJson, json,
                completedResults, failedActivities, recordedSideEffects, scheduledPending, aligner);

        try {
            Object result = workflow.execute(ctx);
            return Commands.Decision.complete(ctx.newSideEffects(), json.write(result));
        } catch (WorkflowBlockedException blocked) {
            if (ctx.pendingSchedule() != null) {
                return Commands.Decision.schedule(ctx.newSideEffects(), ctx.pendingSchedule());
            }
            // Suspended while waiting on an already-scheduled activity; nothing new to do.
            return Commands.Decision.blocked(ctx.newSideEffects());
        } catch (ActivityFailedException afe) {
            // The workflow chose not to handle a permanent activity failure.
            return Commands.Decision.fail(ctx.newSideEffects(), afe.getMessage());
        } catch (RuntimeException bug) {
            // A deterministic bug in workflow code fails the workflow rather than
            // looping forever. (Genuinely transient problems belong in activities.)
            return Commands.Decision.fail(ctx.newSideEffects(),
                    "Workflow code raised: " + bug.getClass().getSimpleName() + ": " + bug.getMessage());
        }
    }
}
