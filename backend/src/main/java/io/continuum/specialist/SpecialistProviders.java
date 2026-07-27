package io.continuum.specialist;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lookup for the registered provider adapters. */
public final class SpecialistProviders {

    private static final Map<String, SpecialistProvider> BY_NAME = new LinkedHashMap<>();

    static {
        register(new RoboflowProvider());
        register(new GenericHttpProvider());
    }

    private SpecialistProviders() {
    }

    private static void register(SpecialistProvider p) {
        BY_NAME.put(p.name(), p);
    }

    public static SpecialistProvider byName(String name) {
        SpecialistProvider p = name == null ? null : BY_NAME.get(name.toLowerCase());
        if (p == null) {
            throw new SpecialistConnectionService.InvalidConnectionException(
                    "Unknown provider '" + name + "'. Known: " + BY_NAME.keySet());
        }
        return p;
    }

    public static List<SpecialistProvider> all() {
        return List.copyOf(BY_NAME.values());
    }

    /** Console catalogue: what a developer can connect to. */
    public static List<Map<String, Object>> catalogue() {
        return all().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("label", p.label());
            m.put("baseUrl", p.defaultBaseUrl());
            m.put("authStyle", p.defaultAuthStyle().name());
            m.put("authParam", p.defaultAuthParam());
            m.put("inputKinds", p.inputKinds());
            m.put("requiresBaseUrl", p.defaultBaseUrl() == null);
            return m;
        }).toList();
    }
}
