package io.continuum.core.engine;

import io.continuum.config.EngineProperties;
import io.continuum.config.WorkerIdentity;
import io.continuum.persistence.entity.WorkflowTaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls the workflow-task queue and runs decisions. A decision is pure replay,
 * so if this worker dies mid-decision the task's visibility timeout lets another
 * worker re-run it harmlessly.
 */
@Component
@ConditionalOnProperty(prefix = "continuum.engine", name = "workers-enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowWorker {

    private static final Logger log = LoggerFactory.getLogger(WorkflowWorker.class);

    private final TaskClaimer claimer;
    private final WorkflowEngine engine;
    private final WorkerIdentity identity;
    private final EngineProperties props;

    public WorkflowWorker(TaskClaimer claimer, WorkflowEngine engine,
                          WorkerIdentity identity, EngineProperties props) {
        this.claimer = claimer;
        this.engine = engine;
        this.identity = identity;
        this.props = props;
    }

    /** The exception and its root cause, which is usually the part that explains it. */
    static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String top = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        if (root == e) {
            return top;
        }
        return top + " (caused by " + root.getClass().getSimpleName()
                + (root.getMessage() == null ? "" : ": " + root.getMessage()) + ")";
    }

    @Scheduled(fixedDelayString = "${continuum.engine.poll-interval-ms:500}")
    public void poll() {
        List<WorkflowTaskEntity> tasks = claimer.claimWorkflowTasks(props.getBatchSize(), identity.id());
        for (WorkflowTaskEntity task : tasks) {
            if (task.getAttempts() > TaskClaimer.MAX_DECISION_ATTEMPTS) {
                // Claimed this often without ever reporting back: each attempt
                // took the process down, or outlived its lease. Running it again
                // is how it would do that once more.
                claimer.decisionFailed(task.getId(), task.getLastError() != null ? task.getLastError()
                        : "Never finished in " + TaskClaimer.MAX_DECISION_ATTEMPTS + " attempts");
                log.error("Decision for workflow {} parked: claimed {} times without finishing",
                        task.getWorkflowId(), task.getAttempts());
                continue;
            }
            try {
                engine.processDecision(task.getWorkflowId());
                claimer.completeWorkflowTask(task.getId());
            } catch (Exception e) {
                String error = describe(e);
                boolean parked = claimer.decisionFailed(task.getId(), error);
                if (parked) {
                    log.error("Decision for workflow {} failed {} times and is parked until the workflow is resumed: {}",
                            task.getWorkflowId(), task.getAttempts(), error, e);
                } else {
                    log.error("Decision failed for workflow {} (attempt {}); will be retried: {}",
                            task.getWorkflowId(), task.getAttempts(), error, e);
                }
            }
        }
    }
}
