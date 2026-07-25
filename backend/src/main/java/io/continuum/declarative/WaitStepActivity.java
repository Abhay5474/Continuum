package io.continuum.declarative;

import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Completes a wait step.
 *
 * <p>The waiting itself is not done here — the engine holds the task invisible
 * until it is due, so nothing blocks a worker and the timer survives a restart.
 * By the time this runs the wait has already elapsed, so it only records that
 * the step is satisfied.
 */
@Component
public class WaitStepActivity implements Activity {

    public static final String TYPE = "declarative.wait";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object execute(String inputJson, ActivityContext ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("waited", true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", 200);
        out.put("body", body);
        return out;
    }

    public record Input(String stepId, int seconds) {
    }
}
