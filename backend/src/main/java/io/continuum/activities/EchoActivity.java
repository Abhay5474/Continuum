package io.continuum.activities;

import io.continuum.chaos.ChaosMonkey;
import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import org.springframework.stereotype.Component;

/**
 * Trivial activity used by the Phase-0 (no-AI) durability demo. It just returns
 * a labelled echo of its input, but being a real activity it participates fully
 * in scheduling, retries, recording and crash recovery.
 */
@Component
public class EchoActivity implements Activity {

    public static final String TYPE = "demo.echo";

    private final ChaosMonkey chaos;

    public EchoActivity(ChaosMonkey chaos) {
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
        return new Output(in.step(), "completed:" + in.step(), ctx.attempt());
    }

    public record Input(String step) {
    }

    public record Output(String step, String message, int attempt) {
    }
}
