package io.continuum.saga;

import io.continuum.declarative.WorkflowSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What to undo, and in what order, when a workflow fails partway.
 *
 * <p>The engine already guarantees each step runs exactly once and survives a
 * crash. What it cannot guarantee is that the <em>set</em> of steps is
 * all-or-nothing, because the calls go to systems that have never heard of this
 * transaction. A workflow that reserves stock, charges a card and then fails to
 * ship has taken someone's money and is holding inventory, and no amount of
 * durability fixes that.
 *
 * <p>You cannot roll back a charge at a payment provider. You can only issue a
 * refund. That is the saga pattern (Garcia-Molina &amp; Salem, 1987): forward
 * steps paired with compensating ones, run in reverse order of completion.
 *
 * <p><b>Reverse order is not a detail.</b> If reserving stock precedes charging,
 * then refunding must precede releasing the reservation — otherwise the stock is
 * free for someone else to buy in the window before the money is returned.
 * Compensations undo a dependency graph, so they follow it backwards.
 *
 * <p><b>Gaps are named, not hidden.</b> A step with no compensation is listed as
 * uncompensated. Reporting "rolled back" when a confirmation email has already
 * gone out is worse than reporting the truth — the operator needs to know what
 * is still out there, and that list is the only place they will find it.
 */
public final class SagaPlan {

    private SagaPlan() {
    }

    /**
     * @param stepId       the forward step this undoes
     * @param call         how to undo it
     */
    public record Compensation(String stepId, WorkflowSpec.Call call) {
    }

    /**
     * @param compensations what to run, in the order to run it
     * @param uncompensated steps that completed and cannot be undone
     * @param complete      whether every completed step can be undone
     */
    public record Plan(List<Compensation> compensations, List<String> uncompensated,
                       boolean complete, String summary) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("compensations", compensations.stream().map(Compensation::stepId).toList());
            m.put("uncompensated", uncompensated);
            m.put("complete", complete);
            m.put("summary", summary);
            return m;
        }
    }

    /**
     * Builds the plan.
     *
     * @param spec      the workflow definition
     * @param completed step ids that finished, in completion order
     * @param failedAt  the step that failed, excluded from compensation — it did
     *                  not complete, so there is nothing of it to undo
     */
    public static Plan forFailure(WorkflowSpec spec, List<String> completed, String failedAt) {
        Map<String, WorkflowSpec.Step> byId = new LinkedHashMap<>();
        if (spec != null) {
            for (WorkflowSpec.Step s : spec.getSteps()) {
                byId.put(s.getId(), s);
            }
        }

        List<Compensation> out = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        List<String> order = completed == null ? List.of() : completed;

        // Reverse order of completion: undo the most recent effect first, so a
        // resource is never released while the thing that paid for it is still
        // outstanding.
        for (int i = order.size() - 1; i >= 0; i--) {
            String id = order.get(i);
            if (id == null || id.equals(failedAt)) {
                continue;
            }
            WorkflowSpec.Step step = byId.get(id);
            if (step == null) {
                // In the definition once, gone now — a version changed under a
                // running workflow. Nameable, not silently skippable.
                gaps.add(id);
                continue;
            }
            if (step.getCompensate() == null || step.getCompensate().getUrl() == null
                    || step.getCompensate().getUrl().isBlank()) {
                gaps.add(id);
                continue;
            }
            out.add(new Compensation(id, step.getCompensate()));
        }

        boolean complete = gaps.isEmpty();
        String summary = out.isEmpty() && gaps.isEmpty()
                ? "Nothing had completed, so there is nothing to undo."
                : complete
                        ? out.size() + " step" + (out.size() == 1 ? "" : "s") + " will be undone, "
                                + "newest first."
                        : out.size() + " step" + (out.size() == 1 ? "" : "s") + " will be undone; "
                                + gaps.size() + " cannot be (" + String.join(", ", gaps)
                                + ") and their effects remain.";
        return new Plan(List.copyOf(out), List.copyOf(gaps), complete, summary);
    }
}
