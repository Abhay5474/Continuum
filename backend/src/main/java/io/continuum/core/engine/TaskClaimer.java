package io.continuum.core.engine;

import io.continuum.config.EngineProperties;
import io.continuum.persistence.entity.*;
import io.continuum.persistence.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

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

    @Transactional
    public List<ActivityTaskEntity> claimActivities(int limit, String workerId) {
        Instant now = Instant.now();
        List<ActivityTaskEntity> claimed = activityTasks.claimBatch(now, limit);
        for (ActivityTaskEntity t : claimed) {
            t.setStatus(TaskStatus.RUNNING);
            t.setLockedBy(workerId);
            t.setLockedUntil(now.plusSeconds(t.getTimeoutSeconds()));
        }
        activityTasks.saveAll(claimed);
        return claimed;
    }

    @Transactional
    public List<WorkflowTaskEntity> claimWorkflowTasks(int limit, String workerId) {
        Instant now = Instant.now();
        List<WorkflowTaskEntity> claimed = workflowTasks.claimBatch(now, limit);
        for (WorkflowTaskEntity t : claimed) {
            t.setStatus(TaskStatus.RUNNING);
            t.setLockedBy(workerId);
            t.setLockedUntil(now.plusSeconds(props.getWorkflowTaskTimeoutSeconds()));
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
            workflowTasks.save(t);
        });
    }
}
