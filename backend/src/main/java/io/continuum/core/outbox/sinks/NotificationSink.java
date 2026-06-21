package io.continuum.core.outbox.sinks;

import io.continuum.chaos.ChaosMonkey;
import io.continuum.core.outbox.DeliveryRecorder;
import io.continuum.core.outbox.OutboxSink;
import io.continuum.persistence.entity.OutboxEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Mock generic notification/webhook channel. */
@Component
public class NotificationSink implements OutboxSink {

    private static final Logger log = LoggerFactory.getLogger(NotificationSink.class);

    private final ChaosMonkey chaos;
    private final DeliveryRecorder recorder;

    public NotificationSink(ChaosMonkey chaos, DeliveryRecorder recorder) {
        this.chaos = chaos;
        this.recorder = recorder;
    }

    @Override
    public String destination() {
        return "notification";
    }

    @Override
    public void deliver(OutboxEntity message) throws Exception {
        chaos.maybeFailSink(destination());
        log.info("🔔 NOTIFICATION delivered (key={}): {}", message.getIdempotencyKey(), message.getPayload());
        recorder.record(destination(), message.getIdempotencyKey(), message.getPayload());
    }
}
