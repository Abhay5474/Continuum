package io.continuum.core.workflow;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Looks up workflow implementations by their {@link Workflow#type()}. */
@Component
public class WorkflowRegistry {

    private final Map<String, Workflow> byType;

    public WorkflowRegistry(List<Workflow> workflows) {
        this.byType = workflows.stream().collect(Collectors.toMap(Workflow::type, Function.identity()));
    }

    public Workflow get(String type) {
        Workflow w = byType.get(type);
        if (w == null) {
            throw new IllegalArgumentException("No workflow registered for type: " + type);
        }
        return w;
    }

    public boolean contains(String type) {
        return byType.containsKey(type);
    }

    public Set<String> types() {
        return byType.keySet();
    }
}
