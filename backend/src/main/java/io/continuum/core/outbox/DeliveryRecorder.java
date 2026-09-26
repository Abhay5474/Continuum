package io.continuum.core.outbox;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Observability for outbox deliveries. Records every successful delivery and how
 * many times each idempotency key was delivered — the latter should always be 1,
 * which is how the chaos demos prove "exactly once".
 *
 * <p>Held in memory, so it is bounded: the most recent {@value #KEEP} deliveries
 * are kept for display, and delivery counts for the last {@value #KEYS}
 * idempotency keys. It used to keep every delivery for the life of the process
 * in a copy-on-write list, so each delivery copied all the ones before it and
 * the heap grew with every workflow ever run.
 */
@Component
public class DeliveryRecorder {

    static final int KEEP = 1_000;
    static final int KEYS = 100_000;

    private final Deque<Delivery> recent = new ArrayDeque<>();
    private final Map<String, Integer> countByKey = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > KEYS;
        }
    };
    private final Set<String> duplicateKeys = new LinkedHashSet<>();
    private long total;

    public void record(String destination, String idempotencyKey, String payload) {
        record(destination, idempotencyKey, payload, null);
    }

    /** @param workflowId the run the delivery belongs to, so an account can be shown its own */
    public synchronized void record(String destination, String idempotencyKey, String payload, String workflowId) {
        int count = countByKey.merge(idempotencyKey, 1, Integer::sum);
        if (count > 1 && duplicateKeys.size() < KEEP) {
            duplicateKeys.add(idempotencyKey);
        }
        recent.addLast(new Delivery(destination, idempotencyKey, payload, count, Instant.now(), workflowId));
        if (recent.size() > KEEP) {
            recent.removeFirst();
        }
        total++;
    }

    /** The most recent deliveries, oldest first. */
    public synchronized List<Delivery> deliveries() {
        return List.copyOf(recent);
    }

    /** Idempotency keys that were (incorrectly) delivered more than once. */
    public synchronized List<String> duplicates() {
        return List.copyOf(duplicateKeys);
    }

    public synchronized int totalDeliveries() {
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public synchronized void clear() {
        recent.clear();
        countByKey.clear();
        duplicateKeys.clear();
        total = 0;
    }

    public record Delivery(String destination, String idempotencyKey, String payload,
                           int deliveryCount, Instant deliveredAt, String workflowId) {
    }
}
