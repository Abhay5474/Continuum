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

    @Scheduled(fixedDelayString = "${continuum.engine.poll-interval-ms:500}")
    public void poll() {
        List<WorkflowTaskEntity> tasks = claimer.claimWorkflowTasks(props.getBatchSize(), identity.id());
        for (WorkflowTaskEntity task : tasks) {
            try {
                engine.processDecision(task.getWorkflowId());
                claimer.completeWorkflowTask(task.getId());
            } catch (Exception e) {
                // Leave the task RUNNING; the recovery sweeper will make it visible again.
                log.error("Decision failed for workflow {} (task {}); will be retried after timeout",
                        task.getWorkflowId(), task.getId(), e);
            }
        }
    }
}
