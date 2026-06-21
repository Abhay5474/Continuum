package io.continuum.core.outbox;

import io.continuum.persistence.entity.OutboxEntity;

/**
 * The actual delivery channel for an outbox message (email gateway, payment
 * processor, webhook, etc.). The dispatcher guarantees a successfully-SENT
 * message is delivered at-most-once thanks to the unique idempotency key.
 */
public interface OutboxSink {

    String destination();

    /** Deliver the message. Throwing causes a retry with backoff. */
    void deliver(OutboxEntity message) throws Exception;
}
