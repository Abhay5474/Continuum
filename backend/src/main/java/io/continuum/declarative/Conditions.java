package io.continuum.declarative;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards on a step: {@code "${steps.risk.score} > 0.8"}.
 *
 * <p>Deliberately a comparison grammar and nothing more — comparisons joined by
 * {@code and}/{@code or}. There is no arithmetic, no function calls and no way
 * to reach anything outside the recorded scope.
 *
 * <p>That restraint is the point. A condition is re-evaluated on every replay, so
 * it must be a pure function of values already in the history; anything that
 * could read the clock, the network or a random source would make the same
 * history produce a different execution and break recovery. It also means a
 * customer-authored string never becomes code the server runs.
 */
public final class Conditions {

    private static final Pattern COMPARISON = Pattern.compile(
            "^\\s*(.+?)\\s*(==|!=|>=|<=|>|<)\\s*(.+?)\\s*$");

    private Conditions() {
    }

    /** A parsed guard. Parsing is separate so authoring errors surface at publish time. */
    public record Expr(List<Clause> clauses, boolean anyOf) {
    }

    public record Clause(String left, String op, String right) {
    }

    /** Throws {@link WorkflowSpec.InvalidSpecException} if the guard is not understood. */
    public static Expr parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new WorkflowSpec.InvalidSpecException("Empty condition.");
        }
        boolean anyOf = raw.matches("(?i).*\\bor\\b.*");
        if (anyOf && raw.matches("(?i).*\\band\\b.*")) {
            throw new WorkflowSpec.InvalidSpecException(
                    "Mixing 'and' with 'or' is not supported; use one or the other: " + raw);
        }
        String[] parts = raw.split(anyOf ? "(?i)\\s+or\\s+" : "(?i)\\s+and\\s+");
        List<Clause> clauses = new ArrayList<>();
        for (String part : parts) {
            Matcher m = COMPARISON.matcher(part);
            if (!m.matches()) {
                throw new WorkflowSpec.InvalidSpecException(
                        "Condition must be comparisons joined by and/or: " + part);
            }
            clauses.add(new Clause(m.group(1).trim(), m.group(2), m.group(3).trim()));
        }
        return new Expr(clauses, anyOf);
    }

    /** Evaluates a guard against recorded values. */
    public static boolean evaluate(String raw, Map<String, Object> scope) {
        Expr expr = parse(raw);
        for (Clause c : expr.clauses()) {
            boolean ok = compare(
                    Templates.resolve(c.left(), scope),
                    c.op(),
                    Templates.resolve(c.right(), scope));
            if (expr.anyOf() && ok) {
                return true;
            }
            if (!expr.anyOf() && !ok) {
                return false;
            }
        }
        return !expr.anyOf();
    }

    private static boolean compare(Object left, String op, Object right) {
        Double ln = number(left);
        Double rn = number(right);
        if (ln != null && rn != null) {
            int cmp = Double.compare(ln, rn);
            return switch (op) {
                case "==" -> cmp == 0;
                case "!=" -> cmp != 0;
                case ">" -> cmp > 0;
                case ">=" -> cmp >= 0;
                case "<" -> cmp < 0;
                case "<=" -> cmp <= 0;
                default -> false;
            };
        }
        String ls = text(left);
        String rs = text(right);
        return switch (op) {
            case "==" -> ls.equals(rs);
            case "!=" -> !ls.equals(rs);
            // Ordering two non-numbers is almost always an authoring mistake, so it
            // is false rather than an arbitrary lexicographic answer.
            default -> false;
        };
    }

    private static Double number(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean) {
            return null;
        }
        try {
            return v == null ? null : Double.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Bare and quoted literals compare the same way, so quoting is optional. */
    private static String text(Object v) {
        String s = v == null ? "null" : String.valueOf(v);
        if (s.length() >= 2
                && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
