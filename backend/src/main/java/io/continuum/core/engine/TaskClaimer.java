package io.continuum.core.engine;

import io.continuum.config.EngineProperties;
import io.continuum.persistence.entity.*;
import io.continuum.persistence.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Atomically claims work from the durable queues.
 *
 * Each claim is its own short transaction: it runs the
 * {@code FOR UPDATE SKIP LOCKED} select and flips the rows to RUNNING with a
 * visibility deadline, then commits. After commit the rows are "owned" by this
 * worker and invisible to others until they complete or their deadline lapses.
 */
@Service
public class TaskClaimer {

    private final ActivityTaskRepository activityTasks;
    private final WorkflowTaskRepository workflowTasks;
    private final OutboxRepository outbox;
    private final EngineProperties props;

    public TaskClaimer(ActivityTaskRepository activityTasks, WorkflowTaskRepository workflowTasks,
                       OutboxRepository outbox, EngineProperties props) {
        this.activityTasks = activityTasks;
        this.workflowTasks = workflowTasks;
        this.outbox = outbox;
        this.props = props;
    }

    /**
     * How long past its timeout a claimed activity stays invisible. The worker
     * stops the activity at its timeout and then records the outcome; the grace
     * covers that write. Only a worker that died leaves the lease to run out.
     */
    public static final int LEASE_GRACE_SECONDS = 30;

    private final AtomicLong claims = new AtomicLong();

    /**
     * Claims runnable activities. Each claim is stamped with its own token
     * ({@code worker#n}), not just the worker's name, so that when a task is
     * recovered and claimed again — even by the same process — the two attempts
     * can be told apart and only the current one may record an outcome.
     */
    @Transactional
    public List<ActivityTaskEntity> claimActivities(int limit, String workerId) {
        Instant now = Instant.now();
        List<ActivityTaskEntity> claimed = activityTasks.claimBatch(now, limit);
        for (ActivityTaskEntity t : claimed) {
            t.setStatus(TaskStatus.RUNNING);
            t.setLockedBy(claimToken(workerId));
            t.setLockedUntil(now.plusSeconds((long) t.getTimeoutSeconds() + LEASE_GRACE_SECONDS));
        }
        activityTasks.saveAll(claimed);
        return claimed;
    }

    private String claimToken(String workerId) {
        String suffix = "#" + Long.toString(claims.incrementAndGet(), 36);
        String base = workerId.length() + suffix.length() > 64 ? workerId.substring(0, 64 - suffix.length()) : workerId;
        return base + suffix;
    }

    @Transactional
    public List<WorkflowTaskEntity> claimWorkflowTasks(int limit, String workerId) {
        Instant now = Instant.now();
        List<WorkflowTaskEntity> claimed = workflowTasks.claimBatch(now, limit);
        for (WorkflowTaskEntity t : claimed) {
            t.setStatus(TaskStatus.RUNNING);
            t.setLockedBy(workerId);
            t.setLockedUntil(now.plusSeconds(props.getWorkflowTaskTimeoutSeconds()));
            t.setAttempts(t.getAttempts() + 1);
            t.setUpdatedAt(now);
        }
        workflowTasks.saveAll(claimed);
        return claimed;
    }

    @Transactional
    public List<OutboxEntity> claimOutbox(int limit) {
        Instant now = Instant.now();
        List<OutboxEntity> claimed = outbox.claimBatch(now, limit);
        for (OutboxEntity o : claimed) {
            // Make invisible briefly while we attempt delivery (recovered on crash).
            o.setVisibleAt(now.plusSeconds(30));
            o.setAttempts(o.getAttempts() + 1);
        }
        outbox.saveAll(claimed);
        return claimed;
    }

    @Transactional
    public void completeWorkflowTask(Long id) {
        workflowTasks.findById(id).ifPresent(t -> {
            t.setStatus(TaskStatus.COMPLETED);
            t.setLockedBy(null);
            t.setLockedUntil(null);
            t.setUpdatedAt(Instant.now());
            workflowTasks.save(t);
            // The workflow moved on, so any decision parked earlier no longer
            // describes it.
            workflowTasks.clearParked(t.getWorkflowId());
        });
    }

    /**
     * How many times a decision may be tried before it is parked. With the
     * backoff below that is about a quarter of an hour of retrying, which rides
     * out a database blip and still stops a decision that fails every time.
     * (Workflow code that throws fails its run instead; what lands here fails
     * around that code — a workflow type no longer deployed, a history that
     * cannot be read, a resolution the healing engine cannot commit.)
     */
    public static final int MAX_DECISION_ATTEMPTS = 8;

    /** Seconds before a failed decision is tried again: 5, 10, 20 … capped at 5 minutes. */
    static long decisionBackoffSeconds(int attempts) {
        return Math.min(300L, 5L << Math.max(0, Math.min(attempts - 1, 10)));
    }

    /**
     * A decision threw. It is tried again after a backoff, or — at the limit —
     * parked with its error: the task is FAILED, the workflow stays RUNNING, and
     * nothing retries it until someone resumes the workflow.
     *
     * @return true when the decision was parked
     */
    @Transactional
    public boolean decisionFailed(Long id, String error) {
        WorkflowTaskEntity t = workflowTasks.findById(id).orElse(null);
        if (t == null) {
            return false;
        }
        Instant now = Instant.now();
        t.setLastError(error == null ? null : error.length() > 2000 ? error.substring(0, 2000) : error);
        t.setLockedBy(null);
        t.setLockedUntil(null);
        t.setUpdatedAt(now);
        boolean park = t.getAttempts() >= MAX_DECISION_ATTEMPTS;
        if (park) {
            t.setStatus(TaskStatus.FAILED);
        } else {
            t.setStatus(TaskStatus.PENDING);
            t.setVisibleAt(now.plusSeconds(decisionBackoffSeconds(t.getAttempts())));
        }
        workflowTasks.save(t);
        return park;
    }
}
