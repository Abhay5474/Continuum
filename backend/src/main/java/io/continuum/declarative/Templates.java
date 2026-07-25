package io.continuum.declarative;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code ${...}} references so a step can use the run's input and
 * the output of steps before it.
 *
 * <p>Two roots are available: {@code input} (what the run was started with) and
 * {@code steps.<id>} (a completed step's parsed response). Resolution is a pure
 * function of recorded values, so it produces identical results on replay.
 *
 * <p>Deliberately not an expression language. There is no arithmetic, no
 * conditionals and no function calls — nothing that could make the same history
 * evaluate differently later, and nothing that could execute author-supplied
 * logic on the server.
 */
public final class Templates {

    private static final Pattern REF = Pattern.compile("\\$\\{([A-Za-z0-9_.\\[\\]-]+)}");

    private Templates() {
    }

    /** Resolves every reference inside a JSON-ish structure, in place of a copy. */
    @SuppressWarnings("unchecked")
    public static Object resolve(Object node, Map<String, Object> scope) {
        if (node instanceof String s) {
            return resolveString(s, scope);
        }
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), resolve(v, scope)));
            return out;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(v -> out.add(resolve(v, scope)));
            return out;
        }
        return node;
    }

    /**
     * A string containing exactly one reference and nothing else resolves to the
     * referenced value with its type intact, so {@code "${steps.a.count}"} stays a
     * number. Mixed text interpolates as a string.
     */
    static Object resolveString(String s, Map<String, Object> scope) {
        Matcher m = REF.matcher(s);
        if (!m.find()) {
            return s;
        }
        if (m.start() == 0 && m.end() == s.length()) {
            return lookup(m.group(1), scope);
        }
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = lookup(m.group(1), scope);
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Walks a dotted path; a missing segment resolves to null rather than throwing. */
    @SuppressWarnings("unchecked")
    static Object lookup(String path, Map<String, Object> scope) {
        Object cur = scope;
        for (String rawPart : path.split("\\.")) {
            String part = rawPart;
            Integer index = null;
            int bracket = part.indexOf('[');
            if (bracket >= 0 && part.endsWith("]")) {
                try {
                    index = Integer.parseInt(part.substring(bracket + 1, part.length() - 1));
                } catch (NumberFormatException e) {
                    return null;
                }
                part = part.substring(0, bracket);
            }
            if (!part.isEmpty()) {
                if (!(cur instanceof Map<?, ?> m)) {
                    return null;
                }
                cur = ((Map<String, Object>) m).get(part);
            }
            if (index != null) {
                if (!(cur instanceof List<?> l) || index < 0 || index >= l.size()) {
                    return null;
                }
                cur = l.get(index);
            }
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }
}
