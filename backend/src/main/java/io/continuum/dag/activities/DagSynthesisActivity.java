package io.continuum.dag.activities;

import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import io.continuum.dag.DagModels.Aggregation;
import io.continuum.dag.DagModels.ClaimScore;
import io.continuum.dag.DagModels.Plan;
import io.continuum.dag.DagModels.SolverOutput;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * V6 Step 5 — human-readable synthesis: a post-hoc narration of the
 * mathematical resolution (why each claim survived or failed). Deterministic
 * template generation — the narration explains the computation, it never
 * re-judges it, so no LLM is consulted and the result is replay-identical.
 */
@Component
public class DagSynthesisActivity implements Activity {

    public static final String TYPE = "dag.synthesize";

    public record Input(Plan plan, List<SolverOutput> solvers, Aggregation aggregation) {
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object execute(String inputJson, ActivityContext ctx) {
        Input in = ctx.input(inputJson, Input.class);
        StringBuilder out = new StringBuilder();

        // The verified answer: conclusions of the surviving reasoning spine.
        for (int claimId : in.aggregation().spine()) {
            in.solvers().stream().filter(s -> s.claimId() == claimId).findFirst()
                    .ifPresent(s -> out.append(s.conclusion()).append(' '));
        }
        if (in.aggregation().spine().isEmpty()) {
            out.append("No claim survived verification — the request could not be answered ")
               .append("with acceptable confidence. ");
        }

        out.append(String.format("%n%n— Verification summary (confidence %.1f%%, uncertainty %s) —%n",
                in.aggregation().finalConfidence() * 100, in.aggregation().uncertainty()));
        for (ClaimScore s : in.aggregation().scores()) {
            String statement = in.plan().claims().stream()
                    .filter(c -> c.id() == s.claimId()).findFirst()
                    .map(c -> c.statement()).orElse("(claim " + s.claimId() + ")");
            out.append(String.format("%s Claim %d [P=%.2f]: %s%n",
                    s.survived() ? "✔" : "✘", s.claimId(), s.posterior(), truncate(statement)));
            for (String note : s.notes()) {
                out.append("    · ").append(note).append('\n');
            }
        }
        return out.toString();
    }

    private static String truncate(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
