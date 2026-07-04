package io.continuum.healing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.continuum.common.Json;
import io.continuum.core.event.EventStore;
import io.continuum.core.event.EventType;
import io.continuum.core.event.Payloads;
import io.continuum.core.workflow.WorkflowExecutor;
import io.continuum.core.workflow.WorkflowRegistry;
import io.continuum.persistence.entity.DivergenceResolutionEntity;
import io.continuum.persistence.entity.WorkflowEventEntity;
import io.continuum.persistence.entity.WorkflowHealingLogEntity;
import io.continuum.persistence.entity.WorkflowInstanceEntity;
import io.continuum.persistence.repository.DivergenceResolutionRepository;
import io.continuum.persistence.repository.WorkflowHealingLogRepository;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The persistence contract of the Paradox Resolution Engine: divergence
 * resolutions are written to BOTH ledger tables when (and only when) healing
 * occurred, within the caller's decision transaction; pristine decisions write
 * nothing at all.
 */
class ParadoxResolutionServiceTest {

    private final Json json = new Json(new ObjectMapper().registerModule(new JavaTimeModule()));
    private final WorkflowHealingLogRepository healingLogs = mock(WorkflowHealingLogRepository.class);
    private final DivergenceResolutionRepository resolutions = mock(DivergenceResolutionRepository.class);
    private final WorkflowInstanceRepository instances = mock(WorkflowInstanceRepository.class);
    private final WorkflowRegistry registry = new WorkflowRegistry(List.of());
    private final EventStore eventStore = mock(EventStore.class);

    private final ParadoxResolutionService service = new ParadoxResolutionService(
            healingLogs, resolutions, instances, registry, new WorkflowExecutor(json), eventStore, json);

    private WorkflowEventEntity scheduledEvent(String workflowId, long eventSeq, long commandSeq, String type) {
        return new WorkflowEventEntity(workflowId, eventSeq, EventType.ACTIVITY_SCHEDULED,
                json.write(new Payloads.ActivityScheduled(commandSeq, type, "{}", 3, 30,
                        workflowId + ":" + commandSeq)));
    }

    @Test
    void openSessionSeedsAlignmentFromHistoryAndDurableLedger() {
        when(healingLogs.findByWorkflowIdOrderByDivergenceSequenceNumberAsc("wf-1"))
                .thenReturn(List.of(new WorkflowHealingLogEntity("wf-1", 2,
                        HealingResolutionType.INSERTION_MAPPED.name(),
                        json.write(new AlignmentMapping(2, 3,
                                HealingResolutionType.INSERTION_MAPPED, -1, "X")))));

        SequenceAlignmentSession session = service.openSession("wf-1", List.of(
                scheduledEvent("wf-1", 1, 1, "A"),
                scheduledEvent("wf-1", 2, 2, "B"),
                scheduledEvent("wf-1", 3, 3, "X")));

        assertEquals(1L, session.alignActivity(1, "A"));
        assertEquals(3L, session.alignActivity(2, "X"), "durable ledger mapping applied verbatim");
        assertEquals(2L, session.alignActivity(3, "B"));
        assertFalse(session.diverged(), "a fully-healed instance replays with no new resolutions");
    }

    @Test
    void commitWritesEveryResolutionToBothLedgerTables() {
        SequenceAlignmentSession session = service.openSession("wf-1", List.of(
                scheduledEvent("wf-1", 1, 1, "A"),
                scheduledEvent("wf-1", 2, 2, "B")));
        when(healingLogs.findByWorkflowIdOrderByDivergenceSequenceNumberAsc("wf-1")).thenReturn(List.of());

        session.alignActivity(1, "A");
        session.alignActivity(2, "X"); // insertion divergence
        assertTrue(session.diverged());

        service.commitResolutions(session, new WorkflowInstanceEntity("wf-1", "Demo", "{}"));

        ArgumentCaptor<WorkflowHealingLogEntity> logRow = ArgumentCaptor.forClass(WorkflowHealingLogEntity.class);
        verify(healingLogs, times(1)).save(logRow.capture());
        assertEquals("wf-1", logRow.getValue().getWorkflowId());
        assertEquals(2L, logRow.getValue().getDivergenceSequenceNumber());
        assertEquals(HealingResolutionType.INSERTION_MAPPED.name(), logRow.getValue().getResolutionType());
        AlignmentMapping stored = json.read(logRow.getValue().getVirtualizedPayloadJson(), AlignmentMapping.class);
        assertEquals(3L, stored.historySeq());

        ArgumentCaptor<DivergenceResolutionEntity> obsRow = ArgumentCaptor.forClass(DivergenceResolutionEntity.class);
        verify(resolutions, times(1)).save(obsRow.capture());
        assertEquals("Demo", obsRow.getValue().getWorkflowType());
    }

    @Test
    void pristineDecisionsCommitNothing() {
        SequenceAlignmentSession session = service.openSession("wf-1", List.of(
                scheduledEvent("wf-1", 1, 1, "A")));
        session.alignActivity(1, "A");

        service.commitResolutions(session, new WorkflowInstanceEntity("wf-1", "Demo", "{}"));

        verify(healingLogs, never()).save(any());
        verify(resolutions, never()).save(any());
    }
}
