package io.continuum.it;

import io.continuum.common.Json;
import io.continuum.core.activity.ActivityContext;
import io.continuum.core.engine.ActivityExecutor;
import io.continuum.core.engine.ActivityWorker;
import io.continuum.core.engine.RecoverySweeper;
import io.continuum.core.engine.TaskClaimer;
import io.continuum.core.engine.WorkflowEngine;
import io.continuum.core.engine.WorkflowWorker;
import io.continuum.core.event.EventType;
import io.continuum.core.outbox.OutboxDispatcher;
import io.continuum.persistence.entity.ActivityTaskEntity;
import io.continuum.persistence.entity.OutboxEntity;
import io.continuum.persistence.entity.OutboxStatus;
import io.continuum.persistence.entity.TaskStatus;
import io.continuum.persistence.entity.WorkflowEventEntity;
import io.continuum.persistence.entity.WorkflowInstanceEntity;
import io.continuum.persistence.entity.WorkflowStatus;
import io.continuum.persistence.repository.ActivityTaskRepository;
import io.continuum.persistence.repository.OutboxRepository;
import io.continuum.persistence.repository.WorkflowEventRepository;
import io.continuum.persistence.repository.WorkflowInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's guarantees, on a real database: a workflow runs to the end, two
 * workers never take the same task, a crashed worker's task is finished by
 * another, a step's result is recorded once however many attempts finish, and
 * an attempt is stopped at its timeout.
 */
@Import(ItWorkflows.class)
class EngineIT extends PostgresIT {

    @Autowired WorkflowEngine engine;
    @Autowired TaskClaimer claimer;
    @Autowired ActivityExecutor executor;
    @Autowired ActivityWorker activityWorker;
    @Autowired WorkflowWorker workflowWorker;
    @Autowired RecoverySweeper sweeper;
    @Autowired OutboxDispatcher outboxDispatcher;
    @Autowired WorkflowInstanceRepository instances;
    @Autowired WorkflowEventRepository events;
    @Autowired ActivityTaskRepository tasks;
    @Autowired OutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.continuum.core.event.EventStore eventStore;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired Json json;

    /**
     * Each test starts from empty queues. The IT database is the tests' own, so
     * anything still open is left over from an earlier, interrupted run.
     */
    @BeforeEach
    void emptyQueues() {
        jdbc.update("UPDATE activity_tasks SET status = 'FAILED' WHERE status IN ('PENDING', 'RUNNING')");
        jdbc.update("UPDATE workflow_tasks SET status = 'COMPLETED' WHERE status IN ('PENDING', 'RUNNING')");
        jdbc.update("UPDATE outbox SET status = 'FAILED' WHERE status = 'PENDING'");
    }

    private String start(String type, String value, int timeoutSeconds) {
        String id = "it-" + UUID.randomUUID();
        engine.startWorkflow(type, json.write(new ItWorkflows.Echo(value, timeoutSeconds)), id);
        return id;
    }

    /** Runs the pollers until the workflow ends, as the scheduler would. */
    private WorkflowInstanceEntity drive(String id, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            workflowWorker.poll();
            activityWorker.poll();
            outboxDispatcher.poll();
            WorkflowInstanceEntity w = instances.findById(id).orElseThrow();
            if (w.getStatus() != WorkflowStatus.RUNNING && activityWorker.inFlight() == 0) {
                outboxDispatcher.poll();
                return w;
            }
            Thread.sleep(50);
        }
        return instances.findById(id).orElseThrow();
    }

    /** Takes the decision that schedules the workflow's next step. */
    private void decide() {
        workflowWorker.poll();
    }

    /** Claims the one open task, which must belong to this workflow. */
    private ActivityExecutor.Attempt claim(String id, String worker) {
        List<ActivityTaskEntity> claimed = claimer.claimActivities(1, worker);
        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getWorkflowId()).isEqualTo(id);
        return ActivityExecutor.Attempt.of(claimed.get(0));
    }

    /** What the recovery sweeper does once a claim's lease has run out. */
    private void leaseRunsOut(String id) {
        jdbc.update("UPDATE activity_tasks SET locked_until = now() - interval '1 second' "
                + "WHERE workflow_id = ? AND status = 'RUNNING'", id);
        sweeper.sweep();
    }

    private List<WorkflowEventEntity> eventsOf(String id, EventType type) {
        return events.findByWorkflowIdOrderBySequenceNumberAsc(id).stream()
                .filter(e -> e.getEventType() == type).toList();
    }

    private ActivityContext ctx(String id) {
        return new ActivityContext(id, firstKey(id), 1, json);
    }

    /** The idempotency key of the workflow's first step. */
    private String firstKey(String id) {
        return tasks.findByWorkflowIdOrderBySequenceNumberAsc(id).get(0).getIdempotencyKey();
    }

    @Test
    void aWorkflowRunsToTheEndAndItsNotificationGoesOutOnce() throws Exception {
        String id = start("it.twoStep", "hi", 10);

        WorkflowInstanceEntity done = drive(id, 20_000);

        assertThat(done.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(done.getResult()).contains("hi!");
        List<OutboxEntity> sent = outbox.findByWorkflowIdOrderByCreatedAtAsc(id);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getStatus()).isEqualTo(OutboxStatus.SENT);
        // Polling again finds nothing to send: the message went out once.
        outboxDispatcher.poll();
        assertThat(outbox.findByWorkflowIdOrderByCreatedAtAsc(id)).hasSize(1);
        assertThat(eventsOf(id, EventType.ACTIVITY_COMPLETED)).hasSize(2);
    }

    @Test
    void twoClaimersNeverTakeTheSameTask() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add(start("it.twoStep", "n" + i, 10));
        }
        for (int i = 0; i < 10; i++) {
            decide();
        }
        Integer open = jdbc.queryForObject("SELECT count(*) FROM activity_tasks WHERE status = 'PENDING'", Integer.class);
        assertThat(open).isEqualTo(40);

        ExecutorService two = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<Long>>> results = new ArrayList<>();
        for (String worker : List.of("claimer-a", "claimer-b")) {
            results.add(two.submit(() -> {
                go.await();
                List<Long> mine = new ArrayList<>();
                List<ActivityTaskEntity> batch;
                do {
                    batch = claimer.claimActivities(3, worker);
                    batch.forEach(t -> mine.add(t.getId()));
                } while (!batch.isEmpty());
                return mine;
            }));
        }
        go.countDown();
        List<Long> all = new ArrayList<>();
        for (Future<List<Long>> f : results) {
            all.addAll(f.get(30, TimeUnit.SECONDS));
        }
        two.shutdown();

        assertThat(all).hasSize(40);
        assertThat(new HashSet<>(all)).hasSize(40);
        assertThat(results.get(0).get()).isNotEmpty();
        assertThat(results.get(1).get()).isNotEmpty();
    }

    @Test
    void aLateResultFromASupersededAttemptIsDiscarded() throws Exception {
        String id = start("it.gated", "x", 5);
        decide();
        ActivityExecutor.Attempt slow = claim(id, "worker-slow");
        leaseRunsOut(id);
        ActivityExecutor.Attempt fresh = claim(id, "worker-fresh");

        executor.complete(fresh, "{\"value\":\"fresh\"}", ctx(id));
        executor.complete(slow, "{\"value\":\"slow\"}", ctx(id));
        executor.fail(slow, new RuntimeException("late failure"));

        List<WorkflowEventEntity> completed = eventsOf(id, EventType.ACTIVITY_COMPLETED);
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).getPayload()).contains("fresh");
        assertThat(eventsOf(id, EventType.ACTIVITY_FAILED)).isEmpty();
        assertThat(tasks.findByWorkflowIdOrderBySequenceNumberAsc(id).get(0).getStatus()).isEqualTo(TaskStatus.COMPLETED);

        WorkflowInstanceEntity done = drive(id, 10_000);
        assertThat(done.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(done.getResult()).contains("fresh");
    }

    @Test
    void theFirstAttemptToSucceedWinsEvenIfItWasTheSupersededOne() throws Exception {
        String id = start("it.gated", "x", 5);
        decide();
        ActivityExecutor.Attempt slow = claim(id, "worker-slow");
        leaseRunsOut(id);
        ActivityExecutor.Attempt fresh = claim(id, "worker-fresh");

        executor.complete(slow, "{\"value\":\"slow\"}", ctx(id));
        executor.complete(fresh, "{\"value\":\"fresh\"}", ctx(id));

        List<WorkflowEventEntity> completed = eventsOf(id, EventType.ACTIVITY_COMPLETED);
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).getPayload()).contains("slow");
    }

    @Test
    void aHistoryThatAlreadyHoldsTwoResultsForOneStepReplaysTheFirst() throws Exception {
        String id = start("it.gated", "x", 5);
        decide();
        ActivityExecutor.Attempt only = claim(id, "worker");
        executor.complete(only, "{\"value\":\"first\"}", ctx(id));
        // What two unfenced workers used to leave behind.
        long seq = tasks.findByWorkflowIdOrderBySequenceNumberAsc(id).get(0).getSequenceNumber();
        tx.executeWithoutResult(t -> eventStore.append(id, EventType.ACTIVITY_COMPLETED,
                new io.continuum.core.event.Payloads.ActivityCompleted(seq, "it.gate", "{\"value\":\"second\"}")));

        WorkflowInstanceEntity done = drive(id, 10_000);

        assertThat(done.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(done.getResult()).contains("first").doesNotContain("second");
    }

    @Test
    void aStaleFailureDoesNotPullTheTaskFromTheCurrentAttempt() {
        String id = start("it.gated", "x", 5);
        decide();
        ActivityExecutor.Attempt slow = claim(id, "worker-slow");
        leaseRunsOut(id);
        ActivityExecutor.Attempt fresh = claim(id, "worker-fresh");

        executor.fail(slow, new RuntimeException("the slow attempt gave up"));

        ActivityTaskEntity task = tasks.findByWorkflowIdOrderBySequenceNumberAsc(id).get(0);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(task.getLockedBy()).isEqualTo(fresh.claim());
        assertThat(task.getRetryCount()).isZero();
        assertThat(eventsOf(id, EventType.ACTIVITY_FAILED)).isEmpty();
    }

    @Test
    void anAttemptIsStoppedAtItsTimeoutAndRetriedAndTheStepCompletesOnce() throws Exception {
        String id = start("it.gated", "x", 1);
        CountDownLatch gate = ItWorkflows.GATES.computeIfAbsent(id, k -> new CountDownLatch(1));
        decide();
        activityWorker.poll();

        // The first attempt blocks on the gate; the worker gives up on it at 1s.
        long deadline = System.currentTimeMillis() + 10_000;
        while (eventsOf(id, EventType.ACTIVITY_FAILED).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        List<WorkflowEventEntity> failed = eventsOf(id, EventType.ACTIVITY_FAILED);
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).getPayload()).contains("Timed out after 1s");

        gate.countDown();
        WorkflowInstanceEntity done = drive(id, 20_000);

        assertThat(done.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(eventsOf(id, EventType.ACTIVITY_COMPLETED)).hasSize(1);
        assertThat(ItWorkflows.runs(firstKey(id))).isEqualTo(2);
    }

    @Test
    void aCrashedWorkersTaskIsRecoveredAndTheWorkflowFinishes() throws Exception {
        String id = start("it.twoStep", "crash", 5);
        decide();
        // A worker claims the first step and dies without a word.
        claim(id, "worker-that-died");
        leaseRunsOut(id);

        WorkflowInstanceEntity done = drive(id, 20_000);

        assertThat(done.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(done.getResult()).contains("crash!");
        assertThat(eventsOf(id, EventType.ACTIVITY_COMPLETED)).hasSize(2);
        // The worker that died never ran the body; the one that recovered it ran it once.
        assertThat(ItWorkflows.runs(firstKey(id))).isEqualTo(1);
    }
}
