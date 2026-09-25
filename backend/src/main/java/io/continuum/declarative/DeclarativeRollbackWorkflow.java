package io.continuum.declarative;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.core.workflow.Workflow;
import io.continuum.core.workflow.WorkflowContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Undoes a cancelled declarative run.
 *
 * <p>A cancelled run is closed: its history ends in a failure, and nothing can
 * be appended to it without changing what its replay would decide. So the
 * rollback is a run of its own, started beside it, carrying everything the
 * compensations need — the pinned spec, the input and each completed step's
 * result, taken from the cancelled run's history — and is as durable as any
 * other: retried, replayed, resumable after a crash, and visible in the
 * console with its own timeline.
 *
 * <p>Its compensations use the cancelled run's id in their idempotency keys, so
 * a receiver that already saw a compensation for a step from the original run
 * treats this one as the duplicate it is.
 */
@Component
public class DeclarativeRollbackWorkflow implements Workflow {

    public static final String TYPE = "DeclarativeRollback";

    private final ObjectMapper mapper;

    public DeclarativeRollbackWorkflow(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object execute(WorkflowContext ctx) {
        Input in = ctx.input(Input.class);
        if (in == null || in.spec() == null || in.cancelledWorkflowId() == null) {
            throw new IllegalArgumentException("A rollback is started by cancelling a Declarative run, "
                    + "not by hand.");
        }
        WorkflowSpec spec = mapper.convertValue(in.spec(), WorkflowSpec.class);
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("input", in.input() == null ? Map.of() : in.input());
        scope.put("steps", in.stepResults() == null ? Map.of() : in.stepResults());

        Compensator.run(ctx, in.cancelledWorkflowId(), in.developerId(), in.definition(), spec, scope,
                in.completed() == null ? List.of() : in.completed(), "(cancelled)",
                in.reason() == null ? "cancelled" : in.reason(),
                in.inFlight() == null ? List.of() : in.inFlight().stream()
                        .map(s -> s + " (was running when cancelled; outcome unknown)").toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rolledBack", in.cancelledWorkflowId());
        out.put("definition", in.definition());
        out.put("plan", io.continuum.saga.SagaPlan.forFailure(spec, in.completed(), "(cancelled)").describe());
        // Not in the plan because nothing can be planned for them.
        out.put("outcomeUnknown", in.inFlight() == null ? List.of() : in.inFlight());
        return out;
    }

    /**
     * @param completed step ids that finished, in completion order
     * @param inFlight  steps that were executing at the moment of cancellation:
     *                  whether they took effect is unknowable from here, so they
     *                  are reported, not guessed at
     */
    public record Input(String cancelledWorkflowId, String definition, int version, String developerId,
                        Object spec, Map<String, Object> input, Map<String, Object> stepResults,
                        List<String> completed, List<String> inFlight, String reason) {
    }
}
