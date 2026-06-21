package io.continuum.activities;

import io.continuum.chaos.ChaosMonkey;
import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import org.springframework.stereotype.Component;

/**
 * Charges a customer — the canonical "never double-charge" side effect.
 *
 * Like email, the charge is delivered through the outbox with a deterministic
 * idempotency key, guaranteeing at-most-once execution across crashes, retries
 * and replays.
 */
@Component
public class PaymentActivity implements Activity {

    public static final String TYPE = "payment.charge";

    private final ChaosMonkey chaos;

    public PaymentActivity(ChaosMonkey chaos) {
        this.chaos = chaos;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object execute(String inputJson, ActivityContext ctx) {
        chaos.maybeFailActivity(TYPE);
        Input in = ctx.input(inputJson, Input.class);
        String chargeId = "charge_" + ctx.idempotencyKey().replace(":", "_");
        ctx.enqueueOutbox("payment", "PaymentRequested",
                new Charge(chargeId, in.customerId(), in.amountCents(), in.currency()));
        return new Output(chargeId, "QUEUED");
    }

    public record Input(String customerId, long amountCents, String currency) {
    }

    public record Charge(String chargeId, String customerId, long amountCents, String currency) {
    }

    public record Output(String chargeId, String status) {
    }
}
