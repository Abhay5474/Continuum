package io.continuum.memory;

/**
 * Hierarchical memory tiers for long-running agents, from hottest to coldest.
 * Entries are promoted/demoted between tiers based on access and age, and only
 * the most relevant are injected into a prompt — keeping context windows small.
 */
public enum MemoryTier {
    /** Immediate scratchpad for the current task. */
    WORKING,
    /** Recent events/interactions for this agent. */
    EPISODIC,
    /** Durable, frequently-useful knowledge. */
    LONG_TERM,
    /** Cold storage; compressed/summarized, rarely retrieved. */
    ARCHIVED
}
