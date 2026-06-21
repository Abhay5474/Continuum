package io.continuum.core.outbox.sinks;

import io.continuum.chaos.ChaosMonkey;
import io.continuum.core.outbox.DeliveryRecorder;
import io.continuum.core.outbox.OutboxSink;
import io.continuum.persistence.entity.OutboxEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Mock payment processor. */
@Component
public class PaymentSink implements OutboxSink {

    private static final Logger log = LoggerFactory.getLogger(PaymentSink.class);

    private final ChaosMonkey chaos;
    private final DeliveryRecorder recorder;

    public PaymentSink(ChaosMonkey chaos, DeliveryRecorder recorder) {
        this.chaos = chaos;
        this.recorder = recorder;
    }

    @Override
    public String destination() {
        return "payment";
    }

    @Override
    public void deliver(OutboxEntity message) throws Exception {
        chaos.maybeFailSink(destination());
        log.info("💳 PAYMENT processed (key={}): {}", message.getIdempotencyKey(), message.getPayload());
        recorder.record(destination(), message.getIdempotencyKey(), message.getPayload());
    }
}
