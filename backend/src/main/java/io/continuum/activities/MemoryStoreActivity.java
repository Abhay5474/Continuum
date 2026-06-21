package io.continuum.activities;

import io.continuum.chaos.ChaosMonkey;
import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import io.continuum.memory.MemoryService;
import io.continuum.memory.MemoryTier;
import org.springframework.stereotype.Component;

/** Durable activity for writing a memory (Extension 5) from inside a workflow. */
@Component
public class MemoryStoreActivity implements Activity {

    public static final String TYPE = "memory.store";

    private final MemoryService memory;
    private final ChaosMonkey chaos;

    public MemoryStoreActivity(MemoryService memory, ChaosMonkey chaos) {
        this.memory = memory;
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
        MemoryTier tier = in.tier() == null ? MemoryTier.EPISODIC : MemoryTier.valueOf(in.tier());
        var saved = memory.store(in.scope(), tier, in.content(), in.salience() == null ? 0.5 : in.salience());
        return new Output(saved.getId(), saved.getTier().name());
    }

    public record Input(String scope, String tier, String content, Double salience) {
    }

    public record Output(Long id, String tier) {
    }
}
