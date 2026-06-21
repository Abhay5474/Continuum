package io.continuum.core.outbox.sinks;

import io.continuum.chaos.ChaosMonkey;
import io.continuum.core.outbox.DeliveryRecorder;
import io.continuum.core.outbox.OutboxSink;
import io.continuum.persistence.entity.OutboxEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Mock email gateway. Subject to chaos failures to exercise dispatcher retries. */
@Component
public class EmailSink implements OutboxSink {

    private static final Logger log = LoggerFactory.getLogger(EmailSink.class);

    private final ChaosMonkey chaos;
    private final DeliveryRecorder recorder;

    public EmailSink(ChaosMonkey chaos, DeliveryRecorder recorder) {
        this.chaos = chaos;
        this.recorder = recorder;
    }

    @Override
    public String destination() {
        return "email";
    }

    @Override
    public void deliver(OutboxEntity message) throws Exception {
        chaos.maybeFailSink(destination());
        log.info("📧 EMAIL sent (key={}): {}", message.getIdempotencyKey(), message.getPayload());
        recorder.record(destination(), message.getIdempotencyKey(), message.getPayload());
    }
}
