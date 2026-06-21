package io.continuum.core.outbox;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Observability for outbox deliveries. Records every successful delivery and how
 * many times each idempotency key was delivered — the latter should always be 1,
 * which is how the chaos demos prove "exactly once".
 */
@Component
public class DeliveryRecorder {

    private final Map<String, AtomicInteger> deliveriesByKey = new ConcurrentHashMap<>();
    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();

    public void record(String destination, String idempotencyKey, String payload) {
        int count = deliveriesByKey.computeIfAbsent(idempotencyKey, k -> new AtomicInteger()).incrementAndGet();
        deliveries.add(new Delivery(destination, idempotencyKey, payload, count, Instant.now()));
    }

    public List<Delivery> deliveries() {
        return List.copyOf(deliveries);
    }

    /** Idempotency keys that were (incorrectly) delivered more than once. */
    public List<String> duplicates() {
        return deliveriesByKey.entrySet().stream()
                .filter(e -> e.getValue().get() > 1)
                .map(Map.Entry::getKey)
                .toList();
    }

    public int totalDeliveries() {
        return deliveries.size();
    }

    public void clear() {
        deliveriesByKey.clear();
        deliveries.clear();
    }

    public record Delivery(String destination, String idempotencyKey, String payload,
                           int deliveryCount, Instant deliveredAt) {
    }
}
