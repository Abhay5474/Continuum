package io.continuum.context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Something the transformer could not determine, kept rather than guessed.
 *
 * <p>This exists because the failure mode of a context layer is not crashing —
 * it is quietly deciding that a column of numbers is US dollars. A model told
 * {@code unit = USD} will reason in dollars and state conclusions in dollars,
 * and nothing downstream will ever question it. A model told
 * {@code unit = UNKNOWN} will say it does not know, which is correct.
 *
 * <p>So every transformer must record what it could not work out, and every
 * rendering must carry those forward. An ambiguity is not a warning about the
 * transformation; it is part of the output.
 */
public record Ambiguity(Kind kind, String where, String detail) {

    public enum Kind {
        /** A column's unit or currency could not be established. */
        UNIT,
        /** Which rows are headers was not clear. */
        HEADER,
        /** A column's type is mixed or unrecognisable. */
        TYPE,
        /** A row may be a subtotal, or may be data. */
        AGGREGATION,
        /** Content was dropped for size, and the caller should know. */
        TRUNCATION,
        /** Structure was present but could not be interpreted. */
        STRUCTURE,
        /** Content was hidden in the source and deliberately excluded. */
        EXCLUDED
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind.name());
        m.put("where", where);
        m.put("detail", detail);
        return m;
    }
}
