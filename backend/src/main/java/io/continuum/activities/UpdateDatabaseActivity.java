package io.continuum.activities;

import io.continuum.chaos.ChaosMonkey;
import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import org.springframework.stereotype.Component;

/**
 * Stands in for a final database write that records the workflow outcome.
 */
@Component
public class UpdateDatabaseActivity implements Activity {

    public static final String TYPE = "customer.update";

    private final ChaosMonkey chaos;

    public UpdateDatabaseActivity(ChaosMonkey chaos) {
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
        return new Output(in.customerId(), in.status(), ctx.idempotencyKey());
    }

    public record Input(String customerId, String status) {
    }

    public record Output(String customerId, String status, String writeToken) {
    }
}
