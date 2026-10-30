package io.continuum.dag.activities;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.common.Json;
import io.continuum.core.activity.ActivityContext;
import io.continuum.dag.DagModels.VerifierOutput;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DagVerifierActivityTest {

    private final Json json = new Json(new ObjectMapper());
    private final DagVerifierActivity verifier = new DagVerifierActivity(json);

    private VerifierOutput check(String check, String statement, String reasoning, String conclusion) {
        String input = json.write(Map.of("claimId", 1, "check", check, "statement", statement,
                "reasoning", reasoning, "conclusion", conclusion));
        return (VerifierOutput) verifier.execute(input, new ActivityContext("wf", "k", 1, json));
    }

    @Test
    void aClaimWithNoNumbersIsNeutralOnTheConstraintCheckNotFailed() {
        VerifierOutput out = check("CONSTRAINT_CHECK", "Saline is safe for cleaning a paw cut",
                "Sterile saline does not harm tissue.", "Saline is safe for cleaning a paw cut.");

        assertThat(out.validity()).isEqualTo(0.5);
        assertThat(out.failureModes()).isEmpty();
    }

    @Test
    void aNumberTheAnswerDroppedStillFailsTheConstraintCheck() {
        VerifierOutput out = check("CONSTRAINT_CHECK", "Bleeding that lasts more than 10 minutes needs a vet",
                "Bleeding that will not stop needs a vet.", "Persistent bleeding needs a vet.");

        assertThat(out.validity()).isLessThan(0.4);
        assertThat(out.failureModes()).isNotEmpty();
    }

    @Test
    void aNumberTheAnswerKeptPassesIt() {
        VerifierOutput out = check("CONSTRAINT_CHECK", "Bleeding that lasts more than 10 minutes needs a vet",
                "If bleeding lasts more than 10 minutes, see a vet.", "After 10 minutes, see a vet.");

        assertThat(out.validity()).isGreaterThan(0.9);
    }
}
