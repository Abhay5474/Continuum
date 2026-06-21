package io.continuum.core.activity;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Looks up activity implementations by their {@link Activity#type()}. */
@Component
public class ActivityRegistry {

    private final Map<String, Activity> byType;

    public ActivityRegistry(List<Activity> activities) {
        this.byType = activities.stream().collect(Collectors.toMap(Activity::type, Function.identity()));
    }

    public Activity get(String type) {
        Activity a = byType.get(type);
        if (a == null) {
            throw new IllegalArgumentException("No activity registered for type: " + type);
        }
        return a;
    }
}
