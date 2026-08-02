package io.continuum.context.transform;

import io.continuum.context.Ambiguity;
import io.continuum.context.CanonicalContext;
import io.continuum.context.CanonicalIncident;
import io.continuum.context.ContextTransformer;
import io.continuum.context.ContextType;
import io.continuum.context.SourceRef;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Raw logs into an incident context.
 *
 * <p>Entirely deterministic, and deliberately so. The obvious alternative — ask
 * a model to summarise the log — is non-reproducible, unauditable, and invents
 * causes that were never in the file. Everything here is counting.
 *
 * <p>Four reductions, in the order they matter:
 *
 * <ol>
 *   <li><b>Templates.</b> Variable parts of a line — ids, durations, addresses,
 *       paths — are masked, and lines that mask to the same shape are one
 *       pattern with a count. This is the essential idea of Drain (He et al.,
 *       ICWS 2017); the masking approach used here is the deterministic,
 *       parameter-free variant, chosen because a prompt that goes into a cache
 *       and an audit trail must not depend on a tree built from arrival
 *       order.</li>
 *   <li><b>Exceptions.</b> Stack traces are collected and grouped by exception
 *       type plus the top frames, following ReBucket (Dang et al., ICSE 2012).
 *       Grouping by message would split one bug across every id it mentioned;
 *       grouping by type alone merges unrelated failures.</li>
 *   <li><b>Correlation.</b> Ids appearing on more than one line are collected,
 *       so one request's path through several services stays visible.</li>
 *   <li><b>Timeline.</b> Errors and first occurrences, in order. The first
 *       occurrence of a failure is the most useful line in the file and the one
 *       tail-truncation always destroys.</li>
 * </ol>
 */
@Component
public class LogTransformer implements ContextTransformer {

    public static final int MAX_BYTES = 32 * 1024 * 1024;

    /** Patterns kept in the rendering. The tail is long and uninformative. */
    private static final int MAX_PATTERNS = 40;
    private static final int MAX_EXCEPTIONS = 12;
    private static final int MAX_TIMELINE = 120;
    private static final int MAX_CORRELATIONS = 10;

    /** Frames compared when grouping exceptions — ReBucket's discriminator. */
    private static final int FINGERPRINT_FRAMES = 4;

    private static final Pattern ISO_TS = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2}:\\d{2}:\\d{2})(?:[.,](\\d{1,9}))?(Z|[+-]\\d{2}:?\\d{2})?");

    private static final Pattern LEVEL = Pattern.compile(
            "\\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|CRITICAL|SEVERE)\\b");

    /** {@code [service]} or {@code service:} near the start of a line. */
    private static final Pattern SERVICE = Pattern.compile(
            "\\[([a-zA-Z][a-zA-Z0-9_.\\-]{1,40})]|\\b([a-z][a-z0-9\\-]{2,30})\\[");

    private static final Pattern STACK_FRAME = Pattern.compile("^\\s+at\\s+(.+)$");
    private static final Pattern EXCEPTION_LINE = Pattern.compile(
            "(?:^|[\\s:])((?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error|Throwable))");

    private static final Pattern UUID_LIKE = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern TRACE_ID = Pattern.compile("\\b[0-9a-f]{16,32}\\b");

    /** Masks applied in order; each replaces a class of variable token. */
    private static final List<Pattern> VARIABLES = List.of(
            UUID_LIKE,
            Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b"),          // IPv4
            Pattern.compile("\\b[0-9a-f]{16,}\\b"),                                  // hex ids
            Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}\\S*"),  // timestamps
            Pattern.compile("\\b\\d+(?:\\.\\d+)?(?:ms|s|us|ns|kb|mb|gb|%)\\b",
                    Pattern.CASE_INSENSITIVE),                                       // durations/sizes
            Pattern.compile("/[\\w./\\-]{2,}"),                                      // paths and URLs
            Pattern.compile("\"[^\"]{0,120}\""),                                     // quoted values
            Pattern.compile("\\b\\d+\\b"));                                          // bare numbers

    @Override
    public String name() {
        return "logs";
    }

    @Override
    public String label() {
        return "Logs → Incident context";
    }

    @Override
    public ContextType produces() {
        return ContextType.INCIDENT;
    }

    /**
     * Whether this looks like a log rather than prose.
     *
     * <p>Deliberately strict. Claiming a document that merely has many lines
     * would produce an "incident" out of a novel, so the test is that a
     * meaningful share of lines carry a timestamp or a severity — the two things
     * that make a line a log line.
     */
    @Override
    public boolean supports(byte[] input, String filename) {
        if (input == null || input.length < 64) {
            return false;
        }
        String head = new String(input, 0, Math.min(input.length, 16384), StandardCharsets.UTF_8);
        String[] lines = head.split("\r?\n");
        if (lines.length < 5) {
            return false;
        }
        int looksLoggy = 0;
        int considered = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            considered++;
            if (considered > 60) {
                break;
            }
            if (ISO_TS.matcher(line).find() || LEVEL.matcher(line).find()
                    || STACK_FRAME.matcher(line).matches()) {
                looksLoggy++;
            }
        }
        return considered >= 5 && looksLoggy / (double) considered >= 0.6;
    }

    @Override
    public String rawTextBaseline(byte[] input, String filename) {
        // The log itself: what the developer would otherwise paste.
        return new String(input, StandardCharsets.UTF_8);
    }

    @Override
    public CanonicalContext transform(byte[] input, String filename) {
        String source = filename == null || filename.isBlank() ? "log" : filename;
        List<Ambiguity> ambiguities = new ArrayList<>();

        byte[] bytes = input;
        if (bytes != null && bytes.length > MAX_BYTES) {
            bytes = java.util.Arrays.copyOf(bytes, MAX_BYTES);
            ambiguities.add(new Ambiguity(Ambiguity.Kind.TRUNCATION, source,
                    "The log is larger than " + (MAX_BYTES / 1024 / 1024)
                            + "MB; only the first part was read."));
        }
        String text = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\r?\n");

        Map<String, Agg> patterns = new LinkedHashMap<>();
        Map<String, ExcAgg> exceptions = new LinkedHashMap<>();
        Map<String, int[]> serviceCounts = new LinkedHashMap<>();
        Map<String, Integer> severities = new LinkedHashMap<>();
        Map<String, Set<String>> correlations = new LinkedHashMap<>();
        Map<String, Integer> correlationCounts = new LinkedHashMap<>();
        List<CanonicalIncident.Event> timeline = new ArrayList<>();
        List<SourceRef> provenance = new ArrayList<>();

        Instant from = null;
        Instant to = null;
        int noTimestamp = 0;
        int lineNo = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            lineNo = i + 1;
            if (line.isBlank()) {
                continue;
            }

            // A stack frame belongs to the exception above it, not to itself.
            if (STACK_FRAME.matcher(line).matches()) {
                continue;
            }

            Instant at = timestamp(line);
            if (at == null) {
                noTimestamp++;
            } else {
                from = from == null || at.isBefore(from) ? at : from;
                to = to == null || at.isAfter(to) ? at : to;
            }

            String level = level(line);
            String service = service(line);
            if (level != null) {
                severities.merge(level, 1, Integer::sum);
            }
            if (service != null) {
                int[] counts = serviceCounts.computeIfAbsent(service, k -> new int[2]);
                counts[0]++;
                if (isError(level)) {
                    counts[1]++;
                }
            }

            for (String id : correlationIds(line)) {
                correlationCounts.merge(id, 1, Integer::sum);
                if (service != null) {
                    correlations.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(service);
                }
            }

            String exceptionType = exceptionType(line);
            if (exceptionType != null) {
                List<String> frames = framesAfter(lines, i);
                String key = exceptionType + "|" + String.join(">", frames);
                final String excType = exceptionType;
                final Instant excAt = at;
                final int excLine = lineNo;
                ExcAgg agg = exceptions.computeIfAbsent(key,
                        k -> new ExcAgg(excType, frames, excAt, excLine));
                agg.count++;
                agg.last = at != null ? at : agg.last;
                if (provenance.size() < 200) {
                    provenance.add(new SourceRef(source, "line " + lineNo, exceptionType));
                }
            }

            String message = message(line);
            String template = templateOf(message);
            final Instant seenAt = at;
            final int seenLine = lineNo;
            Agg agg = patterns.computeIfAbsent(template,
                    k -> new Agg(level, service, seenAt, seenLine, message));
            agg.count++;
            if (at != null) {
                agg.last = at;
                if (agg.first == null) {
                    agg.first = at;
                }
            }

            // The timeline keeps errors and the first sighting of each shape.
            // Tail-truncating a log destroys the first occurrence of a failure,
            // which is where causality starts.
            boolean firstOfShape = agg.count == 1;
            if ((isError(level) || firstOfShape) && timeline.size() < MAX_TIMELINE) {
                timeline.add(new CanonicalIncident.Event(at, level, service, message, lineNo));
            }
        }

        if (noTimestamp > 0 && from == null) {
            ambiguities.add(new Ambiguity(Ambiguity.Kind.STRUCTURE, source,
                    "No line carried a recognisable timestamp, so no time window or ordering "
                            + "could be established."));
        }
        if (patterns.isEmpty()) {
            ambiguities.add(new Ambiguity(Ambiguity.Kind.STRUCTURE, source,
                    "No log lines were found in this input."));
        }

        List<CanonicalIncident.Pattern> topPatterns = patterns.entrySet().stream()
                .map(e -> new CanonicalIncident.Pattern(e.getKey(), e.getValue().count,
                        e.getValue().level, e.getValue().service, e.getValue().first,
                        e.getValue().last, e.getValue().example, e.getValue().firstLine))
                // Errors first at equal frequency: a 400x error matters more
                // than a 400x access-log line, and sorting on count alone buries
                // it under the noise it is competing with.
                .sorted(Comparator.comparing((CanonicalIncident.Pattern p) -> !isError(p.level()))
                        .thenComparing(p -> -p.count()))
                .limit(MAX_PATTERNS)
                .toList();

        List<CanonicalIncident.ExceptionGroup> topExceptions = exceptions.values().stream()
                .map(e -> new CanonicalIncident.ExceptionGroup(e.type, e.frames, e.count,
                        e.first, e.last, e.firstLine))
                .sorted(Comparator.comparingInt(e -> -e.count()))
                .limit(MAX_EXCEPTIONS)
                .toList();

        List<CanonicalIncident.Service> services = serviceCounts.entrySet().stream()
                .map(e -> new CanonicalIncident.Service(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingInt(s -> -s.lines()))
                .toList();

        List<CanonicalIncident.Correlation> topCorrelations = correlationCounts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .sorted(Comparator.comparingInt(e -> -e.getValue()))
                .limit(MAX_CORRELATIONS)
                .map(e -> new CanonicalIncident.Correlation(e.getKey(), e.getValue(),
                        new ArrayList<>(correlations.getOrDefault(e.getKey(), Set.of()))))
                .toList();

        if (timeline.size() >= MAX_TIMELINE) {
            ambiguities.add(new Ambiguity(Ambiguity.Kind.TRUNCATION, source,
                    "More significant events occurred than the timeline holds; only the first "
                            + MAX_TIMELINE + " are listed."));
        }

        timeline.sort(Comparator.comparing(CanonicalIncident.Event::line));

        return new CanonicalIncident(source, new CanonicalIncident.Window(from, to), services,
                topPatterns, topExceptions, timeline, topCorrelations, severities,
                countNonBlank(lines), ambiguities, provenance);
    }

    // --- line parsing --------------------------------------------------------

    /**
     * Masks the variable parts of a message so repetitions collapse.
     *
     * <p>The single highest-leverage step: 6,000 access-log lines differing only
     * in an order id and a duration become one pattern with a count of 6,000.
     */
    static String templateOf(String message) {
        if (message == null) {
            return "";
        }
        String t = message.strip();
        for (Pattern p : VARIABLES) {
            t = p.matcher(t).replaceAll("<*>");
        }
        // Consecutive masks collapse: "<*> <*> <*>" carries no more information
        // than one, and keeping them apart splits identical shapes.
        t = t.replaceAll("(<\\*>[\\s,;:]*){2,}", "<*> ");
        return t.strip();
    }

    static Instant timestamp(String line) {
        Matcher m = ISO_TS.matcher(line);
        if (!m.find()) {
            return null;
        }
        try {
            String date = m.group(1);
            String time = m.group(2);
            String frac = m.group(3);
            String zone = m.group(4);
            String iso = date + "T" + time + (frac == null ? "" : "." + frac);
            if (zone == null) {
                // No offset in the line. Read as UTC rather than the server's
                // zone: the server's zone is not a property of the log.
                return LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        .toInstant(ZoneOffset.UTC);
            }
            return Instant.parse(iso + (zone.equals("Z") ? "Z"
                    : zone.contains(":") ? zone : zone.substring(0, 3) + ":" + zone.substring(3)));
        } catch (Exception e) {
            return null;
        }
    }

    static String level(String line) {
        Matcher m = LEVEL.matcher(line);
        if (!m.find()) {
            return null;
        }
        String l = m.group(1).toUpperCase(Locale.ROOT);
        return switch (l) {
            case "WARNING" -> "WARN";
            case "CRITICAL", "SEVERE" -> "FATAL";
            default -> l;
        };
    }

    static String service(String line) {
        Matcher m = SERVICE.matcher(line);
        while (m.find()) {
            String candidate = m.group(1) != null ? m.group(1) : m.group(2);
            if (candidate == null) {
                continue;
            }
            // A bracketed severity is not a service name.
            if (LEVEL.matcher(candidate).matches()) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /** The message: the line with timestamp, level and service removed. */
    static String message(String line) {
        String m = ISO_TS.matcher(line).replaceAll("");
        m = LEVEL.matcher(m).replaceAll("");
        m = m.replaceAll("^[\\s\\-|:\\[\\]]+", "");
        return m.strip();
    }

    static String exceptionType(String line) {
        Matcher m = EXCEPTION_LINE.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    /** The frames immediately following an exception line, for the fingerprint. */
    static List<String> framesAfter(String[] lines, int index) {
        List<String> frames = new ArrayList<>();
        for (int i = index + 1; i < lines.length && frames.size() < FINGERPRINT_FRAMES; i++) {
            Matcher m = STACK_FRAME.matcher(lines[i]);
            if (!m.matches()) {
                break;
            }
            // The frame without its line number: the same bug moves by a line
            // between builds and must not become a second fingerprint.
            frames.add(m.group(1).replaceAll("\\(.*\\)$", "").strip());
        }
        return frames;
    }

    static List<String> correlationIds(String line) {
        List<String> out = new ArrayList<>();
        Matcher u = UUID_LIKE.matcher(line);
        while (u.find() && out.size() < 4) {
            out.add(u.group());
        }
        if (out.isEmpty()) {
            Matcher t = TRACE_ID.matcher(line);
            while (t.find() && out.size() < 2) {
                out.add(t.group());
            }
        }
        return out;
    }

    private static boolean isError(String level) {
        return "ERROR".equals(level) || "FATAL".equals(level);
    }

    private static int countNonBlank(String[] lines) {
        int n = 0;
        for (String l : lines) {
            if (!l.isBlank()) {
                n++;
            }
        }
        return n;
    }

    private static final class Agg {
        final String level;
        final String service;
        final int firstLine;
        final String example;
        Instant first;
        Instant last;
        int count;

        Agg(String level, String service, Instant first, int firstLine, String example) {
            this.level = level;
            this.service = service;
            this.first = first;
            this.firstLine = firstLine;
            this.example = example;
        }
    }

    private static final class ExcAgg {
        final String type;
        final List<String> frames;
        final Instant first;
        final int firstLine;
        Instant last;
        int count;

        ExcAgg(String type, List<String> frames, Instant first, int firstLine) {
            this.type = type;
            this.frames = frames;
            this.first = first;
            this.last = first;
            this.firstLine = firstLine;
        }
    }
}
