package io.continuum.core.engine;

import io.continuum.config.EngineProperties;
import io.continuum.config.WorkerIdentity;
import io.continuum.persistence.entity.ActivityTaskEntity;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Polls the activity-task queue and executes claimed activities.
 *
 * The flow is deliberately split across transactions:
 * (1) claim + mark RUNNING, (2) record ACTIVITY_STARTED, (3) run the activity
 * with NO transaction held, (4) commit success or failure. If the process dies
 * at any point, the task's visibility timeout returns it to the queue and the
 * idempotency machinery keeps side effects exactly-once.
 *
 * <p><b>Concurrency.</b> Activities used to run inline on Spring's scheduler
 * thread — which is single-threaded by default — so a batch of ten steps ran
 * strictly one after another, and any one of them could hold the only thread in
 * the process for its whole timeout (up to 300s). That blocked workflow
 * decisions, outbox delivery and the recovery sweeper meant to unstick them, and
 * it quietly made {@code executeActivitiesParallel} sequential.
 *
 * <p>Activities now run on a bounded pool. The poller acquires permits before
 * claiming, so it never takes more work than it can start: unclaimed tasks stay
 * visible for another worker instead of sitting in this process's memory.
 */
@Component
@ConditionalOnProperty(prefix = "continuum.engine", name = "workers-enabled", havingValue = "true", matchIfMissing = true)
public class ActivityWorker {

    private static final Logger log = LoggerFactory.getLogger(ActivityWorker.class);

    private final TaskClaimer claimer;
    private final ActivityExecutor executor;
    private final WorkerIdentity identity;
    private final EngineProperties props;

    private final ExecutorService pool;
    private final Semaphore permits;
    /** Live occupancy, published for the console so saturation is observable. */
    private final AtomicLong started = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();

    public ActivityWorker(TaskClaimer claimer, ActivityExecutor executor,
                          WorkerIdentity identity, EngineProperties props) {
        this.claimer = claimer;
        this.executor = executor;
        this.identity = identity;
        this.props = props;
        int n = Math.max(1, props.getActivityConcurrency());
        this.permits = new Semaphore(n);
        this.pool = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "activity-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @Scheduled(fixedDelayString = "${continuum.engine.poll-interval-ms:500}")
    public void poll() {
        // Claim to capacity, never beyond it. Taking work we cannot start would
        // hide it from other workers for the length of the visibility timeout.
        int room = Math.min(props.getBatchSize(), permits.availablePermits());
        if (room <= 0) {
            return;
        }
        List<ActivityTaskEntity> tasks = claimer.claimActivities(room, identity.id());
        for (ActivityTaskEntity task : tasks) {
            if (!permits.tryAcquire()) {
                // Raced with another submission; the task's visibility timeout
                // returns it to the queue rather than being lost.
                log.debug("No capacity for activity task {}; leaving it to time out", task.getId());
                continue;
            }
            started.incrementAndGet();
            pool.execute(() -> {
                try {
                    process(task);
                } finally {
                    permits.release();
                    finished.incrementAndGet();
                }
            });
        }
    }

    /** In-flight activities in this process, for the runtime readout. */
    public long inFlight() {
        return started.get() - finished.get();
    }

    /** Configured ceiling, so the console can show occupancy as a fraction. */
    public int capacity() {
        return Math.max(1, props.getActivityConcurrency());
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

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
