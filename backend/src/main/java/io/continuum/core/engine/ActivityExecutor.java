package io.continuum.core.engine;

import io.continuum.common.Json;
import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import io.continuum.core.activity.ActivityRegistry;
import io.continuum.core.event.EventStore;
import io.continuum.core.event.EventType;
import io.continuum.core.event.Payloads;
import io.continuum.persistence.entity.*;
import io.continuum.persistence.repository.*;
import org.slf4j.Logger;
import io.continuum.portal.TenantContext;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Executes a single claimed activity task and durably records the outcome.
 *
 * The non-deterministic work runs OUTSIDE any database transaction (so a slow
 * LLM call does not hold DB locks). Its outcome is then committed transactionally:
 * on success, the {@code ACTIVITY_COMPLETED} event, any outbox messages and any
 * cost records commit together — the transactional-outbox guarantee. On failure,
 * the task is either rescheduled with exponential backoff or marked terminally
 * failed, and the workflow is woken to observe it.
 */
@Service
public class ActivityExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActivityExecutor.class);
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(2);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final ActivityRegistry registry;
    private final EventStore eventStore;
    private final ActivityTaskRepository activityTasks;
    private final WorkflowTaskRepository workflowTasks;
    private final WorkflowInstanceRepository instances;
    private final OutboxRepository outbox;
    private final CostRecordRepository costs;
    private final Json json;

    public ActivityExecutor(ActivityRegistry registry, EventStore eventStore,
                            ActivityTaskRepository activityTasks, WorkflowTaskRepository workflowTasks,
                            WorkflowInstanceRepository instances, OutboxRepository outbox,
                            CostRecordRepository costs, Json json) {
        this.registry = registry;
        this.eventStore = eventStore;
        this.activityTasks = activityTasks;
        this.workflowTasks = workflowTasks;
        this.instances = instances;
        this.outbox = outbox;
        this.costs = costs;
        this.json = json;
    }

    @Transactional
    public void markStarted(ActivityTaskEntity task, String workerId) {
        eventStore.append(task.getWorkflowId(), EventType.ACTIVITY_STARTED,
                new Payloads.ActivityStarted(task.getSequenceNumber(), task.getActivityType(),
                        task.getRetryCount() + 1, workerId));
    }

    /** Run the activity body. Pure work — no transaction, no DB locks held. */
    public ActivityOutcome runActivity(ActivityTaskEntity task) {
        Activity activity = registry.get(task.getActivityType());
        ActivityContext ctx = new ActivityContext(
                task.getWorkflowId(), task.getIdempotencyKey(), task.getRetryCount() + 1, json);
        // A worker thread is doing this tenant's work, so anything tenant-scoped
        // that runs underneath — fault injection especially — sees the right one.
        String owner = instances.findById(task.getWorkflowId())
                .map(WorkflowInstanceEntity::getDeveloperId)
                .orElse(null);
        TenantContext.set(owner);
        try {
            Object result = activity.execute(task.getInput(), ctx);
            return ActivityOutcome.success(json.write(result), ctx);
        } catch (Exception e) {
            return ActivityOutcome.failure(e, ctx);
        } finally {
            // Workers are pooled and long-lived; a tenant left set here would
            // follow this thread onto the next customer's activity.
            TenantContext.clear();
        }
    }

    @Transactional
    public void complete(Long taskId, String resultJson, ActivityContext ctx) {
        ActivityTaskEntity task = activityTasks.findById(taskId).orElseThrow();
        WorkflowInstanceEntity instance = instances.findByIdForUpdate(task.getWorkflowId()).orElseThrow();

        eventStore.appendLocked(instance, EventType.ACTIVITY_COMPLETED,
                new Payloads.ActivityCompleted(task.getSequenceNumber(), task.getActivityType(), resultJson));

        // Transactional outbox: persist external-message intents atomically with the event.
        for (ActivityContext.OutboxMessage m : ctx.outboxMessages()) {
            if (outbox.findByIdempotencyKey(m.idempotencyKey()).isEmpty()) {
                outbox.save(new OutboxEntity(task.getWorkflowId(), m.destination(),
                        m.eventType(), m.payload(), m.idempotencyKey()));
            }
        }
        // Cost accounting, deduplicated by idempotency key (retries never double-count).
        int idx = 0;
        for (ActivityContext.CostEntry c : ctx.costEntries()) {
            String key = task.getIdempotencyKey() + "#c" + idx++;
            if (costs.findByIdempotencyKey(key).isEmpty()) {
                costs.save(new CostRecordEntity(task.getWorkflowId(), key, c.provider(), c.model(),
                        c.promptTokens(), c.completionTokens(), c.costUsd()));
            }
        }

        task.setStatus(TaskStatus.COMPLETED);
        task.setLockedBy(null);
        task.setLockedUntil(null);
        activityTasks.save(task);
        instances.save(instance);

        // Wake the workflow so it can make the next decision.
        workflowTasks.save(new WorkflowTaskEntity(task.getWorkflowId()));
        log.info("Activity {} (seq {}) completed for workflow {}",
                task.getActivityType(), task.getSequenceNumber(), task.getWorkflowId());
    }

    /** True when the failure (or any cause) is marked as not worth retrying. */
    private static boolean isNonRetryable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof io.continuum.core.activity.NonRetryableFailure) {
                return true;
            }
        }
        return false;
    }

    @Transactional
    public void fail(Long taskId, Throwable error) {
        ActivityTaskEntity task = activityTasks.findById(taskId).orElseThrow();
        int attempt = task.getRetryCount() + 1;
        // A failure the activity marked as settled is terminal on the first
        // attempt: retrying a rejected request only delays the outcome and
        // multiplies load on the target.
        boolean settled = isNonRetryable(error);
        boolean terminal = settled || attempt >= task.getMaxAttempts();
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();

        eventStore.append(task.getWorkflowId(), EventType.ACTIVITY_FAILED,
                new Payloads.ActivityFailed(task.getSequenceNumber(), task.getActivityType(),
                        message, attempt, terminal));

        if (!terminal) {
            long backoffSec = Math.min(
                    MAX_BACKOFF.getSeconds(),
                    BASE_BACKOFF.getSeconds() * (1L << (attempt - 1)));
            Instant nextVisible = Instant.now().plusSeconds(backoffSec);
            task.setRetryCount(attempt);
            task.setStatus(TaskStatus.PENDING);
            task.setVisibleAt(nextVisible);
            task.setLockedBy(null);
            task.setLockedUntil(null);
            activityTasks.save(task);

            eventStore.append(task.getWorkflowId(), EventType.RETRY_SCHEDULED,
                    new Payloads.RetryScheduled(task.getSequenceNumber(), task.getActivityType(),
                            attempt + 1, nextVisible.toString()));
            log.warn("Activity {} (seq {}) failed attempt {}/{}; retrying in {}s: {}",
                    task.getActivityType(), task.getSequenceNumber(), attempt, task.getMaxAttempts(),
                    backoffSec, message);
        } else {
            task.setStatus(TaskStatus.FAILED);
            task.setLockedBy(null);
            task.setLockedUntil(null);
            activityTasks.save(task);
            // Wake the workflow so it can observe the permanent failure (and branch/fail).
            workflowTasks.save(new WorkflowTaskEntity(task.getWorkflowId()));
            log.error("Activity {} (seq {}) permanently failed after {} attempts: {}",
                    task.getActivityType(), task.getSequenceNumber(), attempt, message);
        }
    }

    /** Result of running an activity body, carrying buffered side effects. */
    public static final class ActivityOutcome {
        private final boolean ok;
        private final String result;
        private final Throwable error;
        private final ActivityContext ctx;

        private ActivityOutcome(boolean ok, String result, Throwable error, ActivityContext ctx) {
            this.ok = ok;
            this.result = result;
            this.error = error;
            this.ctx = ctx;
        }

        static ActivityOutcome success(String result, ActivityContext ctx) {
            return new ActivityOutcome(true, result, null, ctx);
        }

        static ActivityOutcome failure(Throwable error, ActivityContext ctx) {
            return new ActivityOutcome(false, null, error, ctx);
        }

        public boolean ok() {
            return ok;
        }

        public String result() {
            return result;
        }

        public Throwable error() {
            return error;
        }

        public ActivityContext ctx() {
            return ctx;
        }
    }
}
