package io.continuum.it;

import io.continuum.common.Json;
import io.continuum.core.engine.ActivityWorker;
import io.continuum.core.engine.WorkflowEngine;
import io.continuum.core.engine.WorkflowWorker;
import io.continuum.core.event.EventStore;
import io.continuum.core.event.EventType;
import io.continuum.core.event.HistoryCache;
import io.continuum.core.event.Payloads;
import io.continuum.persistence.entity.WorkflowInstanceEntity;
import io.continuum.persistence.entity.WorkflowStatus;
import io.continuum.persistence.repository.ActivityTaskRepository;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decisions read cached histories and fetch only what is new. The cache must
 * never make a decision see a history other than the one in the database.
 */
@Import(ItWorkflows.class)
class HistoryCacheIT extends PostgresIT {

    @Autowired WorkflowEngine engine;
    @Autowired WorkflowWorker workflowWorker;
    @Autowired ActivityWorker activityWorker;
    @Autowired WorkflowInstanceRepository instances;
    @Autowired ActivityTaskRepository tasks;
    @Autowired EventStore eventStore;
    @Autowired HistoryCache cache;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired Json json;

    @BeforeEach
    void emptyQueues() {
        jdbc.update("UPDATE activity_tasks SET status = 'FAILED' WHERE status IN ('PENDING', 'RUNNING')");
        jdbc.update("UPDATE workflow_tasks SET status = 'COMPLETED' WHERE status IN ('PENDING', 'RUNNING')");
    }

    private WorkflowInstanceEntity drive(String id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (instances.findById(id).orElseThrow().getStatus() == WorkflowStatus.RUNNING
                && System.currentTimeMillis() < deadline) {
            workflowWorker.poll();
            activityWorker.poll();
            Thread.sleep(20);
        }
        return instances.findById(id).orElseThrow();
    }

    @Test
    void eventsWrittenElsewhereBetweenDecisionsAreSeenByTheNext() {
        String id = "it-" + UUID.randomUUID();
        engine.startWorkflow("it.gated", json.write(new ItWorkflows.Echo("x", 5)), id);
        engine.processDecision(id);                       // schedules the step; history now cached
        assertThat(cache.get(id)).isNotNull();

        // Another worker (another process, even) completes the step.
        var task = tasks.findByWorkflowIdOrderBySequenceNumberAsc(id).get(0);
        tx.executeWithoutResult(t -> eventStore.append(id, EventType.ACTIVITY_COMPLETED,
                new Payloads.ActivityCompleted(task.getSequenceNumber(), "it.gate", "{\"value\":\"from-elsewhere\"}")));

        engine.processDecision(id);

        WorkflowInstanceEntity w = instances.findById(id).orElseThrow();
        assertThat(w.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(w.getResult()).contains("from-elsewhere");
    }

    @Test
    void aWorkflowIdReusedAfterItsRunWasRemovedReadsTheNewRunNotTheCachedOne() throws Exception {
        String id = "it-reused-" + UUID.randomUUID();
        engine.startWorkflow("it.twoStep", json.write(new ItWorkflows.Echo("first", 5)), id);
        assertThat(drive(id).getResult()).contains("first!");
        assertThat(cache.get(id)).isNotNull();

        // The run is purged (retention, or by hand) and the id is used again.
        for (String t : new String[]{"workflow_events", "activity_tasks", "workflow_tasks", "outbox"}) {
            jdbc.update("DELETE FROM " + t + " WHERE workflow_id = ?", id);
        }
        jdbc.update("DELETE FROM workflow_instances WHERE workflow_id = ?", id);
        engine.startWorkflow("it.twoStep", json.write(new ItWorkflows.Echo("second", 5)), id);

        WorkflowInstanceEntity again = drive(id);
        assertThat(again.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(again.getResult()).contains("second!").doesNotContain("first");
    }
}
