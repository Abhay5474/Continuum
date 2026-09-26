package io.continuum.dag.activities;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.common.Json;
import io.continuum.dag.DagModels.Claim;
import io.continuum.dag.DagModels.Plan;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DagPlanActivityTest {

    private final DagPlanActivity planner = new DagPlanActivity(null, new Json(new ObjectMapper()));

    private static void assertUniqueIds(Plan plan) {
        List<Integer> ids = plan.claims().stream().map(Claim::id).toList();
        assertEquals(ids.size(), new HashSet<>(ids).size(), "claim ids must be unique: " + ids);
    }

    @Test
    void fallbackForAPromptWithNoSentenceGivesDistinctIds() {
        // An empty prompt used to produce two claims numbered 1, and saving the
        // trace then failed on the (workflow_id, node_key) unique key.
        for (String prompt : new String[]{"", "   ", "hello"}) {
            Plan plan = DagPlanActivity.fallbackPlan(prompt);
            assertUniqueIds(plan);
            Claim closing = plan.claims().get(plan.claims().size() - 1);
            assertFalse(closing.dependsOn().contains(closing.id()), "a claim cannot depend on itself");
        }
    }

    @Test
    void parsedClaimsAreRenumberedAndDependenciesFollow() {
        String reply = """
                {"claims":[
                  {"id":7,"statement":"A","dependsOn":[],"checks":["LOGIC_CONSISTENCY","LOGIC_CONSISTENCY","MADE_UP"]},
                  {"id":8,"statement":"   ","dependsOn":[7]},
                  {"id":9,"statement":"C","dependsOn":[7,9,42],"checks":["evidence_grounding"]},
                  {"id":9,"statement":"D","dependsOn":[9]}
                ]}""";
        Plan plan = planner.parse("task", reply);
        assertNotNull(plan);
        assertUniqueIds(plan);
        assertEquals(List.of("A", "C", "D"), plan.claims().stream().map(Claim::statement).toList());
        assertEquals(List.of("LOGIC_CONSISTENCY"), plan.claims().get(0).checks(), "duplicates and unknown checks dropped");
        assertEquals(List.of("EVIDENCE_GROUNDING"), plan.claims().get(1).checks());
        assertEquals(List.of(1), plan.claims().get(1).dependsOn(), "7 → 1; itself and unknown ids dropped");
        assertEquals(List.of(2), plan.claims().get(2).dependsOn(), "the first claim numbered 9 is claim 2");
    }
}
