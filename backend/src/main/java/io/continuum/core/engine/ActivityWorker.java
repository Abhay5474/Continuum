package io.continuum.core.engine;

import io.continuum.config.EngineProperties;
import io.continuum.config.WorkerIdentity;
import io.continuum.persistence.entity.ActivityTaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls the activity-task queue and executes claimed activities.
 *
 * The flow is deliberately split across transactions:
 * (1) claim + mark RUNNING, (2) record ACTIVITY_STARTED, (3) run the activity
 * with NO transaction held, (4) commit success or failure. If the process dies
 * at any point, the task's visibility timeout returns it to the queue and the
 * idempotency machinery keeps side effects exactly-once.
 */
@Component
@ConditionalOnProperty(prefix = "continuum.engine", name = "workers-enabled", havingValue = "true", matchIfMissing = true)
public class ActivityWorker {

    private static final Logger log = LoggerFactory.getLogger(ActivityWorker.class);

    private final TaskClaimer claimer;
    private final ActivityExecutor executor;
    private final WorkerIdentity identity;
    private final EngineProperties props;

    public ActivityWorker(TaskClaimer claimer, ActivityExecutor executor,
                          WorkerIdentity identity, EngineProperties props) {
        this.claimer = claimer;
        this.executor = executor;
        this.identity = identity;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${continuum.engine.poll-interval-ms:500}")
    public void poll() {
        List<ActivityTaskEntity> tasks = claimer.claimActivities(props.getBatchSize(), identity.id());
        for (ActivityTaskEntity task : tasks) {
            process(task);
        }
    }

    private void process(ActivityTaskEntity task) {
        try {
            executor.markStarted(task, identity.id());
            ActivityExecutor.ActivityOutcome outcome = executor.runActivity(task);
            if (outcome.ok()) {
                executor.complete(task.getId(), outcome.result(), outcome.ctx());
            } else {
                executor.fail(task.getId(), outcome.error());
            }
        } catch (Exception e) {
            // Unexpected error around persistence; let visibility timeout recover it.
            log.error("Error processing activity task {} ({}); will be recovered",
                    task.getId(), task.getActivityType(), e);
        }
    }
}
