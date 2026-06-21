package io.continuum.provider.model;

/** Provider-agnostic message role. Adapters map these to each vendor's vocabulary. */
public enum Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}
