package io.continuum.activities;

import io.continuum.chaos.ChaosMonkey;
import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import io.continuum.memory.MemoryService;
import org.springframework.stereotype.Component;

/**
 * Durable activity for retrieving a bounded, relevance-ranked memory context
 * (Extension 5). Only the most relevant memories within the char budget are
 * returned, keeping the prompt small for long-running agents.
 */
@Component
public class MemoryRetrieveActivity implements Activity {

    public static final String TYPE = "memory.retrieve";

    private final MemoryService memory;
    private final ChaosMonkey chaos;

    public MemoryRetrieveActivity(MemoryService memory, ChaosMonkey chaos) {
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
        int topK = in.topK() == null ? 5 : in.topK();
        int maxChars = in.maxChars() == null ? 2000 : in.maxChars();
        return memory.buildContext(in.scope(), in.query(), topK, maxChars);
    }

    public record Input(String scope, String query, Integer topK, Integer maxChars) {
    }
}
