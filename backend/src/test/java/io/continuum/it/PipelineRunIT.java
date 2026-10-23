package io.continuum.it;

import io.continuum.persistence.entity.PipelineEntity;
import io.continuum.persistence.repository.PipelineRepository;
import io.continuum.specialist.PipelineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A pipeline run outside any surrounding transaction: each write commits on its
 * own, the run is counted in place, and the trace reads back whole.
 */
class PipelineRunIT extends PostgresIT {

    @Autowired PipelineService pipelines;
    @Autowired PipelineRepository repo;

    @Test
    void runsAreCountedInPlaceAndAnEditBetweenThemSurvives() {
        String dev = "dev_pipe_" + UUID.randomUUID().toString().substring(0, 8);
        String name = "declines-" + UUID.randomUUID().toString().substring(0, 8);
        // No specialists and decline-on-no-evidence: the run ends at the policy
        // and never needs a model, so the test needs nothing outside itself.
        PipelineEntity p = new PipelineEntity(dev, name, null, "text", "first prompt", "[]");
        p.setEnabled(true);
        p.setPolicyEnabled(true);
        p.setDeclineOnNoEvidence(true);
        Long id = repo.save(p).getId();

        PipelineService.Run first = pipelines.run(dev, name, Map.of("text", "hello"), "What is this?");
        pipelines.update(dev, id, null, "edited prompt", null, null, null, null, null, null, null, null);
        PipelineService.Run second = pipelines.run(dev, name, Map.of("text", "hello"), "What is this?");

        PipelineEntity after = repo.findById(id).orElseThrow();
        assertThat(after.getRuns()).isEqualTo(2);
        assertThat(after.getSystemPrompt()).isEqualTo("edited prompt");

        for (PipelineService.Run r : List.of(first, second)) {
            assertThat(r.policy()).isNotNull();
            assertThat(r.chain()).extracting(s -> s.get("kind"))
                    .containsExactly("INPUT", "ENRICHMENT", "POLICY", "OUTPUT");
            assertThat(r.chain()).extracting(s -> s.get("ordinal")).containsExactly(0, 1, 2, 3);
        }
        assertThat(first.traceId()).isNotEqualTo(second.traceId());
    }
}
