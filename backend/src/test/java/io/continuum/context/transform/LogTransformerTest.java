package io.continuum.context.transform;

import io.continuum.context.CanonicalIncident;
import io.continuum.context.RenderBudget;
import io.continuum.context.TokenStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The log transformer is measured against a synthetic outage of the shape a real
 * one has: thousands of near-identical access lines, a few hundred errors that
 * all say the same thing with different ids, and one stack trace repeated
 * throughout.
 *
 * <p>The property that matters most is not the token saving — it is that the
 * <em>first</em> occurrence of the failure survives. Tail-truncation, which is
 * the alternative everyone reaches for, destroys exactly that line.
 */
class LogTransformerTest {

    private final LogTransformer transformer = new LogTransformer();

    /** 6,000 healthy lines, 400 errors, and a stack trace that recurs. */
    private static byte[] outageLog() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 6000; i++) {
            b.append(String.format(
                    "2026-08-02T14:0%d:%02dZ INFO [api-gateway] GET /v1/orders/%d 200 %dms%n",
                    i % 10, i % 60, 100000 + i, 20 + (i % 40)));
        }
        for (int i = 0; i < 400; i++) {
            b.append(String.format(
                    "2026-08-02T14:0%d:%02dZ ERROR [payments] Timeout calling upstream "
                            + "billing-api after %dms%n", 3 + (i % 6), i % 60, 30000));
            if (i % 40 == 0) {
                b.append("java.net.SocketTimeoutException: Read timed out\n");
                b.append("\tat java.base/java.net.SocketInputStream.read(SocketInputStream.java:1)\n");
                b.append("\tat com.acme.payments.UpstreamClient.charge(UpstreamClient.java:88)\n");
                b.append("\tat com.acme.payments.ChargeService.run(ChargeService.java:41)\n");
            }
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("thousands of near-identical lines collapse into one counted pattern")
    void collapsesRepetition() {
        CanonicalIncident i = (CanonicalIncident) transformer.transform(outageLog(), "app.log");

        assertThat(i.totalLines()).isGreaterThan(6000);
        // The whole point: 6,000 access lines differing only in an order id and
        // a duration are one shape, not six thousand.
        assertThat(i.patterns()).hasSizeLessThan(20);
        assertThat(i.patterns()).anySatisfy(p -> assertThat(p.count()).isGreaterThan(5000));
    }

    @Test
    @DisplayName("errors rank above equally frequent noise")
    void errorsRankFirst() {
        CanonicalIncident i = (CanonicalIncident) transformer.transform(outageLog(), "app.log");

        // Sorting on count alone buries a 400x error under a 6000x access log,
        // which is precisely the line a person is looking for.
        assertThat(i.patterns().get(0).level()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("a repeated exception is one fingerprint with a count, not ten copies")
    void fingerprintsExceptions() {
        CanonicalIncident i = (CanonicalIncident) transformer.transform(outageLog(), "app.log");

        assertThat(i.exceptions()).hasSize(1);
        CanonicalIncident.ExceptionGroup g = i.exceptions().get(0);
        assertThat(g.type()).isEqualTo("java.net.SocketTimeoutException");
        assertThat(g.count()).isEqualTo(10);
        // The frames are the discriminator — grouping on the message would split
        // one bug across every id it mentioned.
        assertThat(g.frames()).anySatisfy(f -> assertThat(f).contains("UpstreamClient.charge"));
        // Without the line number: the same bug moves by a line between builds.
        assertThat(g.frames()).allSatisfy(f -> assertThat(f).doesNotContain(".java:"));
    }

    @Test
    @DisplayName("the first occurrence of the failure survives, which truncation destroys")
    void keepsFirstOccurrence() {
        CanonicalIncident i = (CanonicalIncident) transformer.transform(outageLog(), "app.log");

        assertThat(i.timeline()).isNotEmpty();
        assertThat(i.timeline().get(0).line()).isLessThan(50);
        assertThat(i.exceptions().get(0).firstLine()).isPositive();
    }

    @Test
    @DisplayName("services and severities are counted from the lines themselves")
    void countsServicesAndSeverities() {
        CanonicalIncident i = (CanonicalIncident) transformer.transform(outageLog(), "app.log");

        assertThat(i.services()).anySatisfy(s -> assertThat(s.name()).isEqualTo("api-gateway"));
        assertThat(i.services()).anySatisfy(s -> {
            assertThat(s.name()).isEqualTo("payments");
            assertThat(s.errors()).isEqualTo(400);
        });
        assertThat(i.severities()).containsEntry("ERROR", 400);
        assertThat(i.window().from()).isNotNull();
    }

    @Test
    @DisplayName("the reduction is large and is measured, not asserted")
    void reducesTokensDramatically() {
        byte[] log = outageLog();

        String raw = transformer.rawTextBaseline(log, "app.log");
        String rendered = transformer.transform(log, "app.log").render(RenderBudget.standard());
        TokenStats stats = TokenStats.of(raw, rendered);

        assertThat(stats.improved()).isTrue();
        // A 6,400-line log does not fit a prompt; the structured form does.
        assertThat(stats.reduction()).isGreaterThan(0.9);
        assertThat(rendered).contains("SocketTimeoutException");
        assertThat(rendered).contains("400x");
    }

    @Test
    @DisplayName("the rendering says it is a reduction, not a model's summary")
    void renderingIsHonestAboutItself() {
        String rendered = transformer.transform(outageLog(), "app.log")
                .render(RenderBudget.standard());

        assertThat(rendered).contains("not a summary written by a model");
        assertThat(rendered).contains("Counts are exact");
    }

    @Test
    @DisplayName("variable parts of a line are masked so identical shapes merge")
    void masksVariables() {
        assertThat(LogTransformer.templateOf("GET /v1/orders/100034 200 45ms"))
                .isEqualTo(LogTransformer.templateOf("GET /v1/orders/998877 200 12ms"));
        assertThat(LogTransformer.templateOf(
                "user 7f3a1b2c-0000-4444-8888-aaaabbbbcccc logged in"))
                .isEqualTo(LogTransformer.templateOf(
                        "user 11112222-3333-4444-5555-666677778888 logged in"));
    }

    @Test
    @DisplayName("correlation ids that appear on several lines are grouped")
    void groupsCorrelationIds() {
        String log = """
                2026-08-02T10:00:00Z INFO [gateway] req 7f3a1b2c-0000-4444-8888-aaaabbbbcccc start
                2026-08-02T10:00:01Z INFO [payments] req 7f3a1b2c-0000-4444-8888-aaaabbbbcccc charge
                2026-08-02T10:00:02Z ERROR [payments] req 7f3a1b2c-0000-4444-8888-aaaabbbbcccc failed
                2026-08-02T10:00:03Z INFO [gateway] req 11112222-3333-4444-5555-666677778888 start
                2026-08-02T10:00:04Z INFO [gateway] unrelated line
                """;

        CanonicalIncident i = (CanonicalIncident) transformer.transform(
                log.getBytes(StandardCharsets.UTF_8), "t.log");

        assertThat(i.correlations()).anySatisfy(c -> {
            assertThat(c.id()).startsWith("7f3a1b2c");
            assertThat(c.lines()).isEqualTo(3);
            // One request's path across services is the thing an incident
            // investigation actually follows.
            assertThat(c.services()).contains("gateway", "payments");
        });
    }

    @Test
    @DisplayName("a log with no timestamps says so rather than inventing an ordering")
    void reportsMissingTimestamps() {
        String log = """
                ERROR something failed
                INFO something else
                ERROR again
                WARN careful
                INFO fine
                INFO fine again
                """;

        CanonicalIncident i = (CanonicalIncident) transformer.transform(
                log.getBytes(StandardCharsets.UTF_8), "t.log");

        assertThat(i.window().from()).isNull();
        assertThat(i.ambiguities()).anySatisfy(a -> assertThat(a.detail()).contains("timestamp"));
        assertThat(i.window().describe()).isEqualTo("no timestamps found");
    }

    @Test
    @DisplayName("prose is not mistaken for a log")
    void declinesProse() {
        String prose = """
                Continuum is a reliability layer that sits between an application
                and a language model. It does not run models itself. The point of
                the context layer is to turn application data into something a
                model can reason over without losing what the structure meant.
                That is the whole idea, and it is deliberately narrow.
                """;

        assertThat(transformer.supports(prose.getBytes(StandardCharsets.UTF_8), "notes.txt"))
                .isFalse();
        assertThat(transformer.supports(outageLog(), "app.log")).isTrue();
    }

    @Test
    @DisplayName("timestamps without an offset are read as UTC, not as the server's zone")
    void parsesTimestamps() {
        assertThat(LogTransformer.timestamp("2026-08-02T14:03:02Z ERROR x").toString())
                .isEqualTo("2026-08-02T14:03:02Z");
        // The server's timezone is not a property of the log file.
        assertThat(LogTransformer.timestamp("2026-08-02 14:03:02.123 INFO x").toString())
                .startsWith("2026-08-02T14:03:02.123");
        assertThat(LogTransformer.timestamp("no timestamp here")).isNull();
    }

    @Test
    @DisplayName("severity synonyms are normalised so counts do not split")
    void normalisesLevels() {
        assertThat(LogTransformer.level("a WARNING b")).isEqualTo("WARN");
        assertThat(LogTransformer.level("a SEVERE b")).isEqualTo("FATAL");
        assertThat(LogTransformer.level("a CRITICAL b")).isEqualTo("FATAL");
        assertThat(LogTransformer.level("nothing")).isNull();
    }
}
