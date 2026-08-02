package io.continuum.context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a piece of canonical data came from.
 *
 * <p>The single most important property of this layer. A number that reaches a
 * language model without a source is a number nobody can check, and a
 * transformation that cannot answer "where did this come from" has replaced the
 * developer's data with something they have to trust blindly.
 *
 * <p>{@code locator} is deliberately provider-shaped rather than a universal
 * addressing scheme: {@code Q4!H17} means something exact to someone holding
 * the workbook, and flattening it into a generic path would lose the only thing
 * that makes it useful.
 *
 * @param document what the input was called
 * @param locator  the address inside it — {@code Q4!H17}, {@code line 4182},
 *                 {@code message 3}
 * @param detail   why this location, when that is not obvious. Null when it is
 */
public record SourceRef(String document, String locator, String detail) {

    public static SourceRef of(String document, String locator) {
        return new SourceRef(document, locator, null);
    }

    /** How a person would say it: {@code financials.xlsx → Q4!H17}. */
    public String describe() {
        return document + " → " + locator + (detail == null || detail.isBlank() ? "" : " (" + detail + ")");
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("document", document);
        m.put("locator", locator);
        m.put("detail", detail);
        return m;
    }
}
