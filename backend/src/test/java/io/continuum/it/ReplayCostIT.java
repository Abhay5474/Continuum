package io.continuum.it;

import io.continuum.common.Json;
import io.continuum.core.engine.ActivityWorker;
import io.continuum.core.engine.WorkflowEngine;
import io.continuum.core.engine.WorkflowWorker;
import io.continuum.persistence.entity.WorkflowStatus;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** How the cost of one decision grows with the length of a workflow's history. */
@Import(ItWorkflows.class)
class ReplayCostIT extends PostgresIT {

    @Autowired WorkflowEngine engine;
    @Autowired WorkflowWorker workflowWorker;
    @Autowired ActivityWorker activityWorker;
    @Autowired WorkflowInstanceRepository instances;
    @Autowired JdbcTemplate jdbc;
    @Autowired Json json;
    @Autowired io.continuum.core.event.EventStore eventStore;

    @Test
    void aDecisionLateInALongHistoryStaysCheap() throws Exception {
        jdbc.update("UPDATE activity_tasks SET status = 'FAILED' WHERE status IN ('PENDING', 'RUNNING')");
        jdbc.update("UPDATE workflow_tasks SET status = 'COMPLETED' WHERE status IN ('PENDING', 'RUNNING')");
        int steps = Integer.parseInt(System.getProperty("replay.steps", "300"));
        String id = "it-long-" + UUID.randomUUID();
        engine.startWorkflow("it.long", json.write(new ItWorkflows.Long_(steps)), id);

        long started = System.currentTimeMillis();
        while (instances.findById(id).orElseThrow().getStatus() == WorkflowStatus.RUNNING
                && System.currentTimeMillis() - started < 300_000) {
            workflowWorker.poll();
            activityWorker.poll();
            Thread.sleep(5);
        }
        assertThat(instances.findById(id).orElseThrow().getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        long events = jdbc.queryForObject("SELECT count(*) FROM workflow_events WHERE workflow_id = ?", Long.class, id);

        // One more decision on the finished history, timed on its own: the
        // cost every late step of a long run pays.
        // Each timed decision replays the whole run and completes it again, so
        // the run is reopened before every one.
        int n = 5;
        long total = 0;
        for (int i = 0; i < n; i++) {
            jdbc.update("UPDATE workflow_instances SET status = 'RUNNING' WHERE workflow_id = ?", id);
            long t = System.nanoTime();
            engine.processDecision(id);
            total += System.nanoTime() - t;
            assertThat(instances.findById(id).orElseThrow().getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        }
        double perDecisionMs = total / 1e6 / n;
        long h = System.nanoTime();
        for (int i = 0; i < n; i++) {
            eventStore.history(id);
        }
        System.out.printf("REPLAY-COST historyLoad=%.1fms%n", (System.nanoTime() - h) / 1e6 / n);

        System.out.printf("REPLAY-COST steps=%d events=%d total=%dms lastDecision=%.1fms%n",
                steps, events, System.currentTimeMillis() - started, perDecisionMs);
        // Measured locally: about 4 ms at 1,200 steps (3,600 events), from
        // about 20 ms before decisions read only what was new. The bound is
        // loose so a slow CI machine does not fail it; a regression to
        // re-reading and re-parsing everything each time shows up as a
        // linear climb in the printed figure.
        assertThat(perDecisionMs).isLessThan(250);
    }
}
