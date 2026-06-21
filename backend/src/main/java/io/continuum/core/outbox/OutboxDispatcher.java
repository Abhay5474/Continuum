package io.continuum.core.outbox;

import io.continuum.config.EngineProperties;
import io.continuum.core.engine.TaskClaimer;
import io.continuum.persistence.entity.OutboxEntity;
import io.continuum.persistence.entity.OutboxStatus;
import io.continuum.persistence.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Delivers transactional-outbox messages to their sinks.
 *
 * Messages were written atomically with their workflow event, so they are never
 * lost. Here we deliver each at-most-once: on success mark SENT, on failure
 * retry with backoff up to a cap, then mark FAILED. The unique idempotency key
 * on the outbox row ensures the same logical message is never enqueued twice in
 * the first place.
 */
@Component
@ConditionalOnProperty(prefix = "continuum.engine", name = "workers-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int MAX_ATTEMPTS = 8;

    private final TaskClaimer claimer;
    private final OutboxRepository outbox;
    private final EngineProperties props;
    private final Map<String, OutboxSink> sinks;

    public OutboxDispatcher(TaskClaimer claimer, OutboxRepository outbox,
                            EngineProperties props, List<OutboxSink> sinkList) {
        this.claimer = claimer;
        this.outbox = outbox;
        this.props = props;
        this.sinks = sinkList.stream().collect(Collectors.toMap(OutboxSink::destination, Function.identity()));
    }

    @Scheduled(fixedDelayString = "${continuum.engine.poll-interval-ms:500}")
    public void poll() {
        List<OutboxEntity> claimed = claimer.claimOutbox(props.getBatchSize());
        for (OutboxEntity message : claimed) {
            deliverOne(message.getId());
        }
    }

    @Transactional
    public void deliverOne(Long id) {
        OutboxEntity message = outbox.findById(id).orElse(null);
        if (message == null || message.getStatus() != OutboxStatus.PENDING) {
            return;
        }
        OutboxSink sink = sinks.get(message.getDestination());
        if (sink == null) {
            message.setStatus(OutboxStatus.FAILED);
            message.setLastError("No sink registered for destination: " + message.getDestination());
            outbox.save(message);
            return;
        }
        try {
            sink.deliver(message);
            message.setStatus(OutboxStatus.SENT);
            message.setDispatchedAt(Instant.now());
            outbox.save(message);
            log.info("Delivered outbox message {} to {} (key {})",
                    message.getId(), message.getDestination(), message.getIdempotencyKey());
        } catch (Exception e) {
            message.setLastError(e.getMessage());
            if (message.getAttempts() >= MAX_ATTEMPTS) {
                message.setStatus(OutboxStatus.FAILED);
                log.error("Outbox message {} permanently failed after {} attempts",
                        message.getId(), message.getAttempts(), e);
            } else {
                long backoff = Math.min(300, 2L << message.getAttempts());
                message.setVisibleAt(Instant.now().plusSeconds(backoff));
                log.warn("Outbox message {} delivery failed (attempt {}); retrying in {}s: {}",
                        message.getId(), message.getAttempts(), backoff, e.getMessage());
            }
            outbox.save(message);
        }
    }
}
