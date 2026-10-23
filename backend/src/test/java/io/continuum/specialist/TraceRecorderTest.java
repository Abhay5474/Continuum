package io.continuum.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.persistence.entity.TraceStepEntity;
import io.continuum.persistence.repository.TraceStepRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TraceRecorderTest {

    @Test
    void finishingATraceReleasesItsStepCounter() {
        TraceStepRepository repo = mock(TraceStepRepository.class);
        TraceRecorder traces = new TraceRecorder(repo, new ObjectMapper());

        traces.step("t", "dev", TraceStepEntity.Kind.INPUT, "a", null, "OK", null, 0, 0);
        traces.step("t", "dev", TraceStepEntity.Kind.OUTPUT, "b", null, "OK", null, 0, 0);
        traces.finish("t");
        // Only reachable if the counter was dropped: a live one would say 2.
        traces.step("t", "dev", TraceStepEntity.Kind.INPUT, "c", null, "OK", null, 0, 0);

        ArgumentCaptor<TraceStepEntity> saved = ArgumentCaptor.forClass(TraceStepEntity.class);
        verify(repo, times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(TraceStepEntity::getOrdinal)
                .isEqualTo(List.of(0, 1, 0));
    }

    @Test
    void aLabelLongerThanItsColumnIsClippedNotRejected() {
        TraceStepEntity long_ = new TraceStepEntity("t", "dev", 0, TraceStepEntity.Kind.MODEL,
                "m".repeat(500), null, "OK", null, 0, 0);
        TraceStepEntity blank = new TraceStepEntity("t", "dev", 0, TraceStepEntity.Kind.MODEL,
                " ", null, "OK", null, 0, 0);

        assertThat(long_.getLabel()).hasSize(200).endsWith("…");
        assertThat(blank.getLabel()).isEqualTo("MODEL");
    }
}
