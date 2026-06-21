package io.continuum.core.engine;

import io.continuum.persistence.repository.ActivityTaskRepository;
import io.continuum.persistence.repository.WorkflowTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Returns tasks abandoned by crashed workers to their queues.
 *
 * This is what turns "a worker died mid-activity" into "the activity simply runs
 * again somewhere else". Combined with idempotency and deterministic replay, it
 * is the backbone of crash recovery.
 */
@Component
@ConditionalOnProperty(prefix = "continuum.engine", name = "workers-enabled", havingValue = "true", matchIfMissing = true)
public class RecoverySweeper {

    private static final Logger log = LoggerFactory.getLogger(RecoverySweeper.class);

    private final ActivityTaskRepository activityTasks;
    private final WorkflowTaskRepository workflowTasks;

    public RecoverySweeper(ActivityTaskRepository activityTasks, WorkflowTaskRepository workflowTasks) {
        this.activityTasks = activityTasks;
        this.workflowTasks = workflowTasks;
    }

    @Scheduled(fixedDelayString = "${continuum.engine.recovery-interval-ms:5000}")
    @Transactional
    public void sweep() {
        Instant now = Instant.now();
        int activities = activityTasks.recoverTimedOut(now);
        int workflows = workflowTasks.recoverTimedOut(now);
        if (activities > 0 || workflows > 0) {
            log.warn("Recovered {} activity task(s) and {} workflow task(s) from crashed/stuck workers",
                    activities, workflows);
        }
    }
}
