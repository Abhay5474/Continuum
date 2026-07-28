package io.continuum.scheduling;

import io.continuum.persistence.entity.SchedulingSettingEntity;
import io.continuum.persistence.repository.SchedulingSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides which waiting request gets the next free slot.
 *
 * <p>Admission control answers "is there room"; this answers "for whom". Without
 * it the answer is whoever polled first, which means a bulk backfill submitted a
 * second earlier beats an interactive request every time and the person watching
 * a spinner has no idea why.
 *
 * <p>The ordering itself is {@link DeadlineScheduler}: priority band first, then
 * earliest deadline, with waiting time aged into the band so nothing starves.
 * This class is the live part — the register of who is currently waiting, and
 * the tallies the console shows.
 *
 * <p><b>Held in memory, per instance.</b> A waiter only exists while its thread
 * is blocked, so there is nothing to persist and nothing to recover: a restart
 * has no queue to lose because every waiter died with the request that created
 * it. Across several instances each orders its own waiters, which is honest
 * rather than ideal — a global order would need a shared queue and the round
 * trip to reach it would cost more than the ordering saves at a 250 ms wait.
 */
@Service
public class SchedulerService {

    private final SchedulingSettingRepository repo;

    private final Map<String, ConcurrentLinkedQueue<Ticket>> waiting = new ConcurrentHashMap<>();
    private final Map<String, Tally> tallies = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public SchedulerService(SchedulingSettingRepository repo) {
        this.repo = repo;
    }

    private static final class Tally {
        final AtomicLong ordered = new AtomicLong();
        final AtomicLong promoted = new AtomicLong();
        final AtomicLong aged = new AtomicLong();
        final AtomicLong missedDeadline = new AtomicLong();
        final AtomicLong peakWaiting = new AtomicLong();
    }

    /** Raised for a request that cannot make its deadline even if started now. */
    public static class DeadlineUnreachableException extends RuntimeException {
        public DeadlineUnreachableException(String message) {
            super(message);
        }
    }

    /**
     * A place in the queue.
     *
     * <p>{@link AutoCloseable} because a waiter that is not removed blocks every
     * request behind it forever. Always closed in a finally block.
     */
    public final class Ticket implements AutoCloseable {
        private final String key;
        private final long id = seq.incrementAndGet();
        private final DeadlineScheduler.Task task;
        private boolean closed;
        private boolean everBlocked;

        private Ticket(String key, DeadlineScheduler.Task task) {
            this.key = key;
            this.task = task;
        }

        DeadlineScheduler.Task task() {
            return task;
        }

        long id() {
            return id;
        }

        /**
         * Whether this ticket is currently first in line.
         *
         * <p>Recomputed on every poll rather than cached, because aging changes
         * the order while requests are waiting — that is what stops the queue
         * from starving its own tail.
         */
        public boolean isNext(Instant now) {
            ConcurrentLinkedQueue<Ticket> q = waiting.get(key);
            if (q == null || q.size() <= 1) {
                return true;
            }
            Ticket best = null;
            int bestBand = Integer.MIN_VALUE;
            Instant bestDeadline = null;
            for (Ticket t : q) {
                int band = DeadlineScheduler.effectiveBand(t.task, now);
                Instant dl = t.task.deadline() == null ? Instant.MAX : t.task.deadline();
                if (best == null || band > bestBand
                        || (band == bestBand && dl.isBefore(bestDeadline))
                        || (band == bestBand && dl.equals(bestDeadline) && t.id < best.id)) {
                    best = t;
                    bestBand = band;
                    bestDeadline = dl;
                }
            }
            boolean next = best == this;
            if (!next) {
                everBlocked = true;
            } else if (everBlocked) {
                tally(key).promoted.incrementAndGet();
                everBlocked = false;
            }
            if (next && DeadlineScheduler.effectiveBand(task, now) > task.priority().band()) {
                tally(key).aged.incrementAndGet();
            }
            return next;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            ConcurrentLinkedQueue<Ticket> q = waiting.get(key);
            if (q != null) {
                q.remove(this);
            }
        }
    }

    // --- ordering ------------------------------------------------------------

    @Transactional(readOnly = true)
    public boolean enabled(String developerId) {
        return developerId != null
                && repo.findById(developerId).map(SchedulingSettingEntity::isEnabled).orElse(false);
    }

    /**
     * Registers a waiter.
     *
     * @param deadlineMs how long the result stays useful, or null for no deadline
     * @param estimateMs how long the call is expected to take
     * @throws DeadlineUnreachableException when the deadline cannot be met even
     *         with an immediate start — starting it spends a slot on a result
     *         nobody can use and delays the requests that could still make theirs
     */
    public Ticket enqueue(String developerId, String provider, DeadlineScheduler.Priority priority,
                          Long deadlineMs, long estimateMs, Instant now) {
        String key = developerId + "|" + provider;
        Instant deadline = deadlineMs == null ? null : now.plusMillis(deadlineMs);
        Duration estimate = Duration.ofMillis(Math.max(0, estimateMs));

        if (deadline != null && now.plus(estimate).isAfter(deadline)) {
            tally(key).missedDeadline.incrementAndGet();
            throw new DeadlineUnreachableException(String.format(
                    "This request cannot meet its %dms deadline — a call to %s is taking about "
                            + "%dms. It was refused rather than started, so the slot goes to a "
                            + "request that can still use it.",
                    deadlineMs, provider, estimate.toMillis()));
        }

        Ticket t = new Ticket(key, new DeadlineScheduler.Task(
                developerId + ":" + provider, priority, now, deadline, estimate));
        ConcurrentLinkedQueue<Ticket> q = waiting.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
        q.add(t);
        Tally c = tally(key);
        c.ordered.incrementAndGet();
        c.peakWaiting.accumulateAndGet(q.size(), Math::max);
        return t;
    }

    private Tally tally(String key) {
        return tallies.computeIfAbsent(key, k -> new Tally());
    }

    // --- console -------------------------------------------------------------

    @Transactional
    public Map<String, Object> configure(String developerId, Boolean enabled) {
        SchedulingSettingEntity cfg = repo.findById(developerId)
                .orElseGet(() -> new SchedulingSettingEntity(developerId));
        if (enabled != null) {
            cfg.setEnabled(enabled);
        }
        repo.save(cfg);
        return status(developerId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(String developerId) {
        SchedulingSettingEntity cfg = repo.findById(developerId)
                .orElseGet(() -> new SchedulingSettingEntity(developerId));
        Instant now = Instant.now();
        String prefix = developerId + "|";

        List<Map<String, Object>> providers = new ArrayList<>();
        for (Map.Entry<String, Tally> e : tallies.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            Tally t = e.getValue();
            ConcurrentLinkedQueue<Ticket> q = waiting.get(e.getKey());

            List<DeadlineScheduler.Task> tasks = new ArrayList<>();
            if (q != null) {
                for (Ticket tk : q) {
                    tasks.add(tk.task());
                }
            }
            List<Map<String, Object>> order = new ArrayList<>();
            for (DeadlineScheduler.Decision d : DeadlineScheduler.order(tasks, now)) {
                order.add(d.describe());
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", e.getKey().substring(prefix.length()));
            m.put("waiting", tasks.size());
            m.put("peakWaiting", t.peakWaiting.get());
            m.put("ordered", t.ordered.get());
            m.put("promoted", t.promoted.get());
            m.put("aged", t.aged.get());
            m.put("missedDeadline", t.missedDeadline.get());
            m.put("queue", order);
            providers.add(m);
        }
        providers.sort((a, b) -> String.valueOf(a.get("provider")).compareTo(String.valueOf(b.get("provider"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.isEnabled());
        out.put("agingStepSeconds", DeadlineScheduler.AGING_STEP.toSeconds());
        out.put("providers", providers);
        return out;
    }

    /** Clears the tallies. The live queue is not touched — it is in use. */
    public Map<String, Object> reset(String developerId) {
        tallies.keySet().removeIf(k -> k.startsWith(developerId + "|"));
        return status(developerId);
    }
}
