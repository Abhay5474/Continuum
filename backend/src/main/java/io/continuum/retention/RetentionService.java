package io.continuum.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the database from growing without end.
 *
 * <p>Every wake-up of a workflow writes a {@code workflow_tasks} row, and every
 * gateway call a {@code gateway_requests} row; none were ever removed. This
 * deletes, in small batches so no table is locked for long:
 * <ul>
 *   <li>decision tasks that finished more than {@code decision-task-days} ago —
 *       pure queue bookkeeping, shown nowhere;</li>
 *   <li>gateway request logs older than {@code request-log-days} (default 90,
 *       comfortably longer than a billing period; 0 keeps them);</li>
 *   <li>finished workflows — their events, tasks, outbox messages and traces —
 *       older than {@code finished-workflow-days}. Off (0) by default: a run's
 *       history is what the console shows, so removing it is the operator's
 *       choice. Cost records are always kept, for billing.</li>
 * </ul>
 * Running workflows are never touched, whatever their age.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    /**
     * Tables that hold a finished workflow's history, children before the
     * instance itself (only {@code workflow_healing_logs} has a foreign key,
     * but the order keeps it right if more are added).
     */
    static final List<String> WORKFLOW_TABLES = List.of(
            "workflow_healing_logs", "workflow_events", "activity_tasks", "workflow_tasks", "outbox",
            "replay_verification_reports", "dag_edges", "dag_nodes", "dag_runs");

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final int decisionTaskDays;
    private final int requestLogDays;
    private final int finishedWorkflowDays;
    private final int webhookDeliveryDays;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean();

    /** Purged runs leave the decision cache too (it would reload them anyway). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.continuum.core.event.HistoryCache historyCache;

    public RetentionService(JdbcTemplate jdbc,
                            @Value("${continuum.retention.enabled:true}") boolean enabled,
                            @Value("${continuum.retention.decision-task-days:1}") int decisionTaskDays,
                            @Value("${continuum.retention.request-log-days:90}") int requestLogDays,
                            @Value("${continuum.retention.finished-workflow-days:0}") int finishedWorkflowDays,
                            @Value("${continuum.retention.batch-size:2000}") int batchSize) {
        this(jdbc, enabled, decisionTaskDays, requestLogDays, finishedWorkflowDays, 30, batchSize);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RetentionService(JdbcTemplate jdbc,
                            @Value("${continuum.retention.enabled:true}") boolean enabled,
                            @Value("${continuum.retention.decision-task-days:1}") int decisionTaskDays,
                            @Value("${continuum.retention.request-log-days:90}") int requestLogDays,
                            @Value("${continuum.retention.finished-workflow-days:0}") int finishedWorkflowDays,
                            @Value("${continuum.retention.webhook-delivery-days:30}") int webhookDeliveryDays,
                            @Value("${continuum.retention.batch-size:2000}") int batchSize) {
        this.webhookDeliveryDays = Math.max(0, webhookDeliveryDays);
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.decisionTaskDays = Math.max(1, decisionTaskDays);
        this.requestLogDays = Math.max(0, requestLogDays);
        this.finishedWorkflowDays = Math.max(0, finishedWorkflowDays);
        this.batchSize = Math.max(100, batchSize);
    }

    /** Every six hours, starting fifteen minutes after startup so it never competes with it. */
    @Scheduled(fixedDelayString = "${continuum.retention.interval-ms:21600000}",
            initialDelayString = "${continuum.retention.first-run-ms:900000}")
    public void scheduled() {
        if (enabled) {
            run("SCHEDULED");
        }
    }

    /** The settings, for the console. */
    public Map<String, Object> settings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("decisionTaskDays", decisionTaskDays);
        m.put("requestLogDays", requestLogDays);
        m.put("finishedWorkflowDays", finishedWorkflowDays);
        m.put("webhookDeliveryDays", webhookDeliveryDays);
        m.put("running", running.get());
        return m;
    }

    /** The latest passes, newest first. */
    public List<Map<String, Object>> recentRuns(int limit) {
        return jdbc.queryForList("SELECT id, trigger, started_at AS \"startedAt\", finished_at AS \"finishedAt\", "
                + "deleted, error FROM retention_runs ORDER BY id DESC LIMIT ?", Math.max(1, Math.min(limit, 50)));
    }

    /**
     * One pass. Returns rows deleted per table, or null if a pass is already
     * running in this process.
     */
    public Map<String, Integer> run(String trigger) {
        if (!running.compareAndSet(false, true)) {
            return null;
        }
        Long runId = jdbc.queryForObject("INSERT INTO retention_runs (trigger) VALUES (?) RETURNING id", Long.class, trigger);
        Map<String, Integer> deleted = new LinkedHashMap<>();
        String error = null;
        try {
            Instant now = Instant.now();
            deleted.put("workflow_tasks", finishedDecisionTasks(now.minus(Duration.ofDays(decisionTaskDays))));
            if (requestLogDays > 0) {
                deleted.put("gateway_requests", batched("gateway_requests", "created_at < ?",
                        Timestamp.from(now.minus(Duration.ofDays(requestLogDays)))));
            }
            if (webhookDeliveryDays > 0) {
                deleted.put("webhook_deliveries", batched("webhook_deliveries", "attempted_at < ?",
                        Timestamp.from(now.minus(Duration.ofDays(webhookDeliveryDays)))));
            }
            if (finishedWorkflowDays > 0) {
                deleted.putAll(finishedWorkflows(now.minus(Duration.ofDays(finishedWorkflowDays))));
            }
            log.info("Retention ({}) removed {}", trigger, deleted);
            return deleted;
        } catch (RuntimeException e) {
            error = e.getMessage();
            log.warn("Retention ({}) stopped: {}", trigger, e.getMessage());
            throw e;
        } finally {
            jdbc.update("UPDATE retention_runs SET finished_at = now(), deleted = ?, error = ? WHERE id = ?",
                    deleted.toString(), error, runId);
            running.set(false);
        }
    }

    /** Finished decisions, and parked ones whose workflow has since ended. */
    private int finishedDecisionTasks(Instant cutoff) {
        int n = batched("workflow_tasks", "status = 'COMPLETED' AND updated_at < ?", Timestamp.from(cutoff));
        n += batched("workflow_tasks", "status = 'FAILED' AND updated_at < ? AND workflow_id IN "
                + "(SELECT workflow_id FROM workflow_instances WHERE status <> 'RUNNING')", Timestamp.from(cutoff));
        return n;
    }

    private Map<String, Integer> finishedWorkflows(Instant cutoff) {
        Map<String, Integer> out = new LinkedHashMap<>();
        WORKFLOW_TABLES.forEach(t -> out.put(t, 0));
        out.put("workflow_instances", 0);
        while (true) {
            List<String> ids = jdbc.queryForList("SELECT workflow_id FROM workflow_instances "
                    + "WHERE status <> 'RUNNING' AND updated_at < ? ORDER BY updated_at LIMIT ?",
                    String.class, Timestamp.from(cutoff), batchSize);
            if (ids.isEmpty()) {
                return out;
            }
            String[] array = ids.toArray(String[]::new);
            for (String table : WORKFLOW_TABLES) {
                out.merge(table, jdbc.update("DELETE FROM " + table + " WHERE workflow_id = ANY (?)",
                        ps -> ps.setArray(1, ps.getConnection().createArrayOf("varchar", array))), Integer::sum);
            }
            if (historyCache != null) {
                ids.forEach(historyCache::remove);
            }
            out.merge("workflow_instances", jdbc.update("DELETE FROM workflow_instances WHERE workflow_id = ANY (?)",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("varchar", array))), Integer::sum);
            if (ids.size() < batchSize) {
                return out;
            }
        }
    }

    /** Deletes matching rows a batch at a time, so no statement holds its locks for long. */
    private int batched(String table, String where, Object arg) {
        int total = 0;
        while (true) {
            int n = jdbc.update("DELETE FROM " + table + " WHERE id IN (SELECT id FROM " + table
                    + " WHERE " + where + " LIMIT " + batchSize + ")", arg);
            total += n;
            if (n < batchSize) {
                return total;
            }
        }
    }
}
