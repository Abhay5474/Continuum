package io.continuum.it;

import io.continuum.api.WorkflowQueryService;
import io.continuum.api.dto.Dtos;
import io.continuum.common.Json;
import io.continuum.core.engine.TaskClaimer;
import io.continuum.core.engine.WorkflowEngine;
import io.continuum.core.engine.ActivityWorker;
import io.continuum.core.engine.WorkflowWorker;
import io.continuum.persistence.entity.WorkflowStatus;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import io.continuum.retention.RetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A workflow whose code keeps failing stops being retried and says why, and
 * can be resumed once fixed. Retention removes bookkeeping and old logs, and
 * never a running workflow.
 */
@Import(ItWorkflows.class)
class StuckAndRetentionIT extends PostgresIT {

    @Autowired WorkflowEngine engine;
    @Autowired WorkflowWorker workflowWorker;
    @Autowired ActivityWorker activityWorker;
    @Autowired WorkflowQueryService query;
    @Autowired io.continuum.core.outbox.OutboxDispatcher outboxDispatcher;
    @Autowired WorkflowInstanceRepository instances;
    @Autowired RetentionService retention;
    @Autowired JdbcTemplate jdbc;
    @Autowired Json json;

    @BeforeEach
    void emptyQueues() {
        jdbc.update("UPDATE activity_tasks SET status = 'FAILED' WHERE status IN ('PENDING', 'RUNNING')");
        jdbc.update("UPDATE workflow_tasks SET status = 'COMPLETED' WHERE status IN ('PENDING', 'RUNNING')");
    }

    private String start(String type) {
        String id = "it-" + UUID.randomUUID();
        engine.startWorkflow(type, json.write(new ItWorkflows.Echo("v", 5)), id);
        return id;
    }

    /** One decision attempt, skipping the backoff wait. */
    private void attempt(String id) {
        jdbc.update("UPDATE workflow_tasks SET visible_at = now() WHERE workflow_id = ? AND status = 'PENDING'", id);
        workflowWorker.poll();
    }

    /**
     * Workflow code that throws already fails its run. What used to loop
     * forever is a decision failing outside that code — here, a run whose
     * workflow type is no longer deployed.
     */
    @Test
    void aDecisionThatKeepsFailingIsParkedWithItsErrorAndCanBeResumedOnceFixed() throws Exception {
        String id = start("it.twoStep");
        jdbc.update("UPDATE workflow_instances SET workflow_type = 'it.removedInThisDeploy' WHERE workflow_id = ?", id);

        attempt(id);
        Map<String, Object> first = jdbc.queryForMap(
                "SELECT status, attempts, last_error, visible_at > now() AS later FROM workflow_tasks WHERE workflow_id = ?", id);
        assertThat(first.get("status")).as("retried, not left RUNNING until a lease runs out").isEqualTo("PENDING");
        assertThat(first.get("attempts")).isEqualTo(1);
        assertThat((String) first.get("last_error")).contains("it.removedInThisDeploy");
        assertThat(first.get("later")).as("after a backoff").isEqualTo(true);

        for (int i = 1; i < TaskClaimer.MAX_DECISION_ATTEMPTS; i++) {
            attempt(id);
        }
        Map<String, Object> parked = jdbc.queryForMap("SELECT status, attempts FROM workflow_tasks WHERE workflow_id = ?", id);
        assertThat(parked.get("status")).isEqualTo("FAILED");
        assertThat(parked.get("attempts")).isEqualTo(TaskClaimer.MAX_DECISION_ATTEMPTS);
        // Parked means nothing claims it any more.
        attempt(id);
        assertThat(jdbc.queryForObject("SELECT attempts FROM workflow_tasks WHERE workflow_id = ?", Integer.class, id))
                .isEqualTo(TaskClaimer.MAX_DECISION_ATTEMPTS);

        Dtos.WorkflowDetail detail = query.detail(id);
        assertThat(detail.summary().status()).isEqualTo("RUNNING");
        assertThat(detail.summary().stuck()).isTrue();
        assertThat(detail.stuck().attempts()).isEqualTo(TaskClaimer.MAX_DECISION_ATTEMPTS);
        assertThat(detail.stuck().error()).contains("No workflow registered");
        assertThat(query.list(500).stream().filter(w -> w.workflowId().equals(id)).findFirst().orElseThrow().stuck()).isTrue();

        // The type is deployed again, and the workflow resumed.
        jdbc.update("UPDATE workflow_instances SET workflow_type = 'it.twoStep' WHERE workflow_id = ?", id);
        assertThat(engine.resume(id)).isTrue();
        long deadline = System.currentTimeMillis() + 15_000;
        while (instances.findById(id).orElseThrow().getStatus() == WorkflowStatus.RUNNING
                && System.currentTimeMillis() < deadline) {
            workflowWorker.poll();
            activityWorker.poll();
            Thread.sleep(50);
        }
        assertThat(instances.findById(id).orElseThrow().getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(query.detail(id).stuck()).isNull();
        assertThat(engine.resume(id)).as("nothing left to resume").isFalse();
    }

    @Test
    void retentionRemovesOldBookkeepingAndLogsButNeverARunningWorkflow() {
        String running = start("it.gated");
        jdbc.update("UPDATE workflow_tasks SET status = 'COMPLETED', updated_at = now() - interval '3 days' WHERE workflow_id = ?", running);
        jdbc.update("UPDATE workflow_instances SET updated_at = now() - interval '400 days' WHERE workflow_id = ?", running);
        String recent = start("it.gated");
        jdbc.update("UPDATE workflow_tasks SET status = 'COMPLETED' WHERE workflow_id = ?", recent);
        String dev = "it-dev-" + UUID.randomUUID();
        jdbc.update("INSERT INTO gateway_requests (developer_id, created_at) VALUES (?, now() - interval '200 days'), (?, now())", dev, dev);

        Map<String, Integer> deleted = retention.run("TEST");

        assertThat(deleted.get("workflow_tasks")).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_tasks WHERE workflow_id = ?", Integer.class, running)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_tasks WHERE workflow_id = ?", Integer.class, recent))
                .as("finished only a moment ago").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_requests WHERE developer_id = ?", Integer.class, dev)).isEqualTo(1);
        // Finished-workflow removal is off by default: the running one is there either way.
        assertThat(instances.findById(running)).isPresent();
        List<Map<String, Object>> runs = retention.recentRuns(1);
        assertThat(runs.get(0).get("trigger")).isEqualTo("TEST");
        assertThat(runs.get(0).get("finishedAt")).isNotNull();
    }

    @Test
    void finishedWorkflowsAreRemovedOnlyWhenTheOperatorAsksAndRunningOnesNever() throws Exception {
        String finished = start("it.twoStep");
        long deadline = System.currentTimeMillis() + 15_000;
        while (instances.findById(finished).orElseThrow().getStatus() == WorkflowStatus.RUNNING
                && System.currentTimeMillis() < deadline) {
            workflowWorker.poll();
            activityWorker.poll();
            outboxDispatcher.poll();
            Thread.sleep(50);
        }
        assertThat(instances.findById(finished).orElseThrow().getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        String stillRunning = start("it.gated");
        jdbc.update("UPDATE workflow_instances SET updated_at = now() - interval '60 days' WHERE workflow_id IN (?, ?)",
                finished, stillRunning);

        RetentionService keepThirtyDays = new RetentionService(jdbc, true, 1, 90, 30, 100);
        Map<String, Integer> deleted = keepThirtyDays.run("TEST");

        assertThat(deleted.get("workflow_instances")).isGreaterThanOrEqualTo(1);
        assertThat(instances.findById(finished)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_events WHERE workflow_id = ?", Integer.class, finished)).isZero();
        assertThat(instances.findById(stillRunning)).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_events WHERE workflow_id = ?", Integer.class, stillRunning))
                .isPositive();
    }
}
