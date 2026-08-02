package io.continuum.context;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logs, reduced to the thing a person actually reasons about during an outage.
 *
 * <p>Ten thousand log lines are both far too many tokens and about 98%
 * repetition. The usual response — truncate to the last 500 lines — throws away
 * the first occurrence of the failure, which is the single most useful line in
 * the file. Sending a summary written by another model is worse: it is
 * non-deterministic, unauditable, and it hallucinates causes.
 *
 * <p>What survives here is what log analysis research established matters:
 * <b>templates</b> (Drain, He et al. ICWS 2017 — the same line with different
 * ids is one event, not six thousand), <b>exception fingerprints</b> (ReBucket,
 * Dang et al. ICSE 2012 — crashes group by call stack, not by message), and
 * <b>first occurrence ordering</b>, because "what happened first" is the
 * question causality is answered from.
 *
 * <p>Nothing here interprets. The transformer does not decide what caused the
 * outage; it removes the 98% that stops a model from being able to.
 */
public record CanonicalIncident(String sourceName, Window window, List<Service> services,
                                List<Pattern> patterns, List<ExceptionGroup> exceptions,
                                List<Event> timeline, List<Correlation> correlations,
                                Map<String, Integer> severities, int totalLines,
                                List<Ambiguity> ambiguities,
                                List<SourceRef> provenance) implements CanonicalContext {

    /** When the log covers, or null when no line carried a parseable timestamp. */
    public record Window(Instant from, Instant to) {

        public String describe() {
            if (from == null || to == null) {
                return "no timestamps found";
            }
            Duration d = Duration.between(from, to);
            return from + " → " + to + "  (" + human(d) + ")";
        }

        private static String human(Duration d) {
            long s = Math.max(0, d.getSeconds());
            if (s < 60) {
                return s + "s";
            }
            return (s / 60) + "m " + (s % 60) + "s";
        }
    }

    public record Service(String name, int lines, int errors) {
    }

    /**
     * One log line shape and how often it occurred.
     *
     * @param template  the line with its variable parts masked
     * @param first     when it was first seen. The field that matters most:
     *                  a failure's first occurrence is where causality starts
     */
    public record Pattern(String template, int count, String level, String service,
                          Instant first, Instant last, String example, int firstLine) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("template", template);
            m.put("count", count);
            m.put("level", level);
            m.put("service", service);
            m.put("first", first == null ? null : first.toString());
            m.put("last", last == null ? null : last.toString());
            m.put("example", example);
            m.put("firstLine", firstLine);
            return m;
        }
    }

    /**
     * Distinct exceptions, grouped by call stack rather than by message.
     *
     * <p>Grouping by message would split one bug across every id it mentioned;
     * grouping by exception class alone would merge unrelated timeouts. The top
     * frames are the discriminator, which is ReBucket's finding.
     */
    public record ExceptionGroup(String type, List<String> frames, int count,
                                 Instant first, Instant last, int firstLine) {

        public String fingerprint() {
            return type + "|" + String.join(">", frames);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("frames", frames);
            m.put("count", count);
            m.put("first", first == null ? null : first.toString());
            m.put("last", last == null ? null : last.toString());
            m.put("firstLine", firstLine);
            return m;
        }
    }

    /** An id appearing on several lines — one request's path through the system. */
    public record Correlation(String id, int lines, List<String> services) {
    }

    /** A single line kept verbatim in the timeline, because it was significant. */
    public record Event(Instant at, String level, String service, String message, int line) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", at == null ? null : at.toString());
            m.put("level", level);
            m.put("service", service);
            m.put("message", message);
            m.put("line", line);
            return m;
        }
    }

    @Override
    public ContextType type() {
        return ContextType.INCIDENT;
    }

    @Override
    public Map<String, Object> structure() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lines", totalLines);
        m.put("patterns", patterns.size());
        m.put("exceptions", exceptions.size());
        m.put("services", services.size());
        m.put("correlations", correlations.size());
        m.put("timelineEvents", timeline.size());
        m.put("errors", severities.getOrDefault("ERROR", 0) + severities.getOrDefault("FATAL", 0));
        return m;
    }

    @Override
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type().name());
        m.put("sourceName", sourceName);
        m.put("window", Map.of("from", window.from() == null ? null : window.from().toString(),
                "to", window.to() == null ? null : window.to().toString()));
        m.put("services", services.stream().map(s -> Map.of(
                "name", s.name(), "lines", s.lines(), "errors", s.errors())).toList());
        m.put("severities", severities);
        m.put("patterns", patterns.stream().map(Pattern::toMap).toList());
        m.put("exceptions", exceptions.stream().map(ExceptionGroup::toMap).toList());
        m.put("timeline", timeline.stream().map(Event::toMap).toList());
        m.put("correlations", correlations.stream().map(c -> Map.of(
                "id", c.id(), "lines", c.lines(), "services", c.services())).toList());
        m.put("structure", structure());
        m.put("ambiguities", ambiguities.stream().map(Ambiguity::toMap).toList());
        return m;
    }

    @Override
    public String render(RenderBudget budget) {
        StringBuilder b = new StringBuilder();
        int limit = budget.maxChars();

        b.append("INCIDENT CONTEXT: ").append(sourceName).append('\n');
        b.append(window.describe()).append(", ").append(totalLines).append(" lines.\n");

        if (!services.isEmpty()) {
            b.append("Services: ");
            b.append(services.stream().limit(8)
                    .map(s -> s.name() + " (" + s.lines() + (s.errors() > 0
                            ? ", " + s.errors() + " errors)" : ")"))
                    .reduce((x, y) -> x + ", " + y).orElse(""));
            b.append('\n');
        }
        if (!severities.isEmpty()) {
            b.append("Severity: ");
            b.append(severities.entrySet().stream()
                    .map(e -> e.getKey() + " " + e.getValue())
                    .reduce((x, y) -> x + ", " + y).orElse(""));
            b.append('\n');
        }

        if (!exceptions.isEmpty()) {
            // Before the patterns: an exception with a stack is almost always
            // the most informative thing in a log, and burying it under six
            // thousand access-log lines is how it gets missed.
            b.append("\nEXCEPTIONS (").append(exceptions.size())
                    .append(exceptions.size() == 1 ? " distinct)\n" : " distinct)\n");
            for (ExceptionGroup g : exceptions) {
                if (b.length() > limit) {
                    break;
                }
                b.append("  ").append(g.count()).append("x  ").append(g.type()).append('\n');
                for (String f : g.frames()) {
                    b.append("      at ").append(f).append('\n');
                }
                if (g.first() != null) {
                    b.append("      first ").append(g.first())
                            .append(g.last() != null && !g.last().equals(g.first())
                                    ? "  last " + g.last() : "")
                            .append("  (line ").append(g.firstLine()).append(")\n");
                }
            }
        }

        if (!patterns.isEmpty()) {
            b.append("\nREPEATED PATTERNS (").append(patterns.size()).append(" distinct shapes)\n");
            for (Pattern p : patterns) {
                if (b.length() > limit) {
                    b.append("  [more patterns omitted at the configured budget]\n");
                    break;
                }
                b.append("  ").append(p.count()).append("x  ")
                        .append(p.level() == null ? "" : p.level() + " ")
                        .append(p.service() == null ? "" : p.service() + "  ")
                        .append('"').append(p.template()).append('"');
                if (p.first() != null) {
                    b.append("   first ").append(p.first());
                }
                b.append('\n');
            }
        }

        if (!correlations.isEmpty()) {
            b.append("\nCORRELATED REQUESTS\n");
            for (Correlation c : correlations) {
                if (b.length() > limit) {
                    break;
                }
                b.append("  ").append(c.id()).append("  ").append(c.lines())
                        .append(" lines across ").append(String.join(", ", c.services())).append('\n');
            }
        }

        if (!timeline.isEmpty()) {
            b.append("\nTIMELINE (significant events, in order)\n");
            for (Event e : timeline) {
                if (b.length() > limit) {
                    b.append("  [timeline truncated at the configured budget — later events "
                            + "exist and are not shown]\n");
                    break;
                }
                b.append("  ").append(e.at() == null ? "?" : e.at().toString()).append("  ")
                        .append(e.level() == null ? "" : e.level()).append("  ")
                        .append(e.service() == null ? "" : e.service() + "  ")
                        .append(e.message()).append('\n');
            }
        }

        if (!ambiguities.isEmpty()) {
            b.append("\nUNRESOLVED\n");
            for (Ambiguity a : ambiguities) {
                b.append("  - ").append(a.where()).append(": ").append(a.detail()).append('\n');
            }
        }

        b.append("\nThis is a structured reduction of the log, not a summary written by a model. "
                + "Counts are exact. Lines not shown were repetitions of the patterns above.\n");
        return b.toString();
    }
}
