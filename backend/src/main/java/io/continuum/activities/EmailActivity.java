package io.continuum.activities;

import io.continuum.chaos.ChaosMonkey;
import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import org.springframework.stereotype.Component;

/**
 * Sends an email — the textbook "must not happen twice" side effect.
 *
 * It does NOT call an email gateway inline. It enqueues a message in the
 * transactional outbox keyed by the activity's idempotency key, so even if the
 * worker crashes and the activity re-runs, the email is delivered exactly once.
 */
@Component
public class EmailActivity implements Activity {

    public static final String TYPE = "email.send";

    private final ChaosMonkey chaos;

    public EmailActivity(ChaosMonkey chaos) {
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
        ctx.enqueueOutbox("email", "EmailRequested", in);
        return new Output(true, ctx.idempotencyKey());
    }

    public record Input(String to, String subject, String body) {
    }

    public record Output(boolean queued, String idempotencyKey) {
    }
}
