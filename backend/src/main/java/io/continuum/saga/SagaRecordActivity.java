package io.continuum.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the rollback report.
 *
 * <p>It is an activity rather than a plain call because a workflow may not touch
 * the database directly: on replay the write would happen again and the console
 * would show the same rollback several times. As an activity it runs once and
 * replays from history like any other step.
 */
@Component
public class SagaRecordActivity implements Activity {

    public static final String TYPE = "saga.record";

    private final SagaService saga;
    private final ObjectMapper mapper;

    public SagaRecordActivity(SagaService saga, ObjectMapper mapper) {
        this.saga = saga;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object execute(String inputJson, ActivityContext ctx) {
        Input in;
        try {
            in = mapper.readValue(inputJson, Input.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unreadable saga record input: " + e.getMessage());
        }
        SagaPlan.Plan plan = new SagaPlan.Plan(
                in.compensated() == null ? List.of()
                        : in.compensated().stream()
                                .map(id -> new SagaPlan.Compensation(id, null)).toList(),
                in.uncompensated() == null ? List.of() : in.uncompensated(),
                in.complete(), in.summary());
        saga.record(in.developerId(), in.workflowId(), in.definition(), in.failedStep(), plan);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recorded", true);
        out.put("complete", in.complete());
        return out;
    }

    public record Input(String developerId, String workflowId, String definition, String failedStep,
                        List<String> compensated, List<String> uncompensated, boolean complete,
                        String summary) {
    }
}
