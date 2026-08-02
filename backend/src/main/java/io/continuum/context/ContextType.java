package io.continuum.context;

/**
 * The shape of a canonical context.
 *
 * <p>Typed rather than one universal structure. A spreadsheet, an incident and
 * an email thread have genuinely nothing in common below the surface, and
 * forcing them into a single generic JSON tree would produce something that
 * describes all three badly — the caller would get {@code nodes} and
 * {@code children} where they wanted columns, or messages, or a timeline.
 *
 * <p>What they share is the {@link CanonicalContext} contract: render for a
 * model, describe for a machine, declare what was ambiguous, say where it came
 * from. That is the right amount of commonality.
 */
public enum ContextType {

    /** A spreadsheet or delimited file, with its layout resolved into meaning. */
    SEMANTIC_TABLE("Semantic table", "Headers, units and measures recovered from layout"),

    /** Logs or events, reduced to patterns, exceptions and a timeline. */
    INCIDENT("Incident context", "Repetition collapsed, exceptions fingerprinted, ordering kept"),

    /** An email or chat thread, deduplicated into an actual conversation. */
    CONVERSATION("Conversation", "Quoted history and signatures removed, chronology kept"),

    /** Recognised, but nothing better than the original could be produced. */
    PASSTHROUGH("Passthrough", "No transformer improved on the original");

    private final String label;
    private final String summary;

    ContextType(String label, String summary) {
        this.label = label;
        this.summary = summary;
    }

    public String label() {
        return label;
    }

    public String summary() {
        return summary;
    }
}
