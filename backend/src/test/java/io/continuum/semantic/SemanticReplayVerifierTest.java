package io.continuum.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.activities.LlmActivity;
import io.continuum.common.Json;
import io.continuum.core.event.EventType;
import io.continuum.core.event.Payloads;
import io.continuum.declarative.DeterministicReplayVerifier;
import io.continuum.persistence.entity.ReplayVerificationReportEntity;
import io.continuum.persistence.entity.WorkflowEventEntity;
import io.continuum.persistence.entity.WorkflowInstanceEntity;
import io.continuum.persistence.repository.ReplayVerificationReportRepository;
import io.continuum.persistence.repository.WorkflowEventRepository;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import io.continuum.provider.ProviderRouter;
import io.continuum.provider.model.LlmResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticReplayVerifierTest {

    @Test
    void reportsBelongToTheAccountThatOwnsTheRun() {
        ObjectMapper mapper = new ObjectMapper();
        Json json = new Json(mapper);
        WorkflowEventRepository events = mock(WorkflowEventRepository.class);
        WorkflowInstanceRepository instances = mock(WorkflowInstanceRepository.class);
        ReplayVerificationReportRepository reports = mock(ReplayVerificationReportRepository.class);
        ProviderRouter router = mock(ProviderRouter.class);
        DeterministicReplayVerifier deterministic = mock(DeterministicReplayVerifier.class);

        WorkflowInstanceEntity run = new WorkflowInstanceEntity("wf-1", "CustomerAnalysis", "{}");
        run.setDeveloperId("dev-owner");
        when(instances.findById("wf-1")).thenReturn(Optional.of(run));
        when(deterministic.verify(any(), any(), any())).thenReturn(DeterministicReplayVerifier.Result.notApplicable());

        String input = json.write(new LlmActivity.Input(null, "Assess risk", null, null, null));
        String output = json.write(new LlmActivity.Output("Assessment: LOW RISK.", "mock", "m", 1, 1, 0.0));
        when(events.findByWorkflowIdOrderBySequenceNumberAsc("wf-1")).thenReturn(List.of(
                new WorkflowEventEntity("wf-1", 1, EventType.ACTIVITY_SCHEDULED,
                        json.write(new Payloads.ActivityScheduled(2, LlmActivity.TYPE, input, 3, 30, "k:2"))),
                new WorkflowEventEntity("wf-1", 2, EventType.ACTIVITY_COMPLETED,
                        json.write(new Payloads.ActivityCompleted(2, LlmActivity.TYPE, output)))));
        when(router.complete(any())).thenReturn(
                new LlmResponse("Assessment: LOW RISK.", List.of(), 1, 1, "mock", "m", "stop"));
        when(reports.save(any())).thenAnswer(i -> i.getArgument(0));

        new SemanticReplayVerifier(events, instances, reports, new SemanticComparator(mapper), router, deterministic, json)
                .verify("wf-1", ReplayVerificationPolicy.defaults());

        ArgumentCaptor<ReplayVerificationReportEntity> saved = ArgumentCaptor.forClass(ReplayVerificationReportEntity.class);
        verify(reports).save(saved.capture());
        assertEquals("dev-owner", saved.getValue().getDeveloperId());
    }
}
