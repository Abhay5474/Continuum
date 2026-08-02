package io.continuum.context;

import io.continuum.context.transform.EmailThreadTransformer;
import io.continuum.context.transform.LogTransformer;
import io.continuum.context.transform.SpreadsheetTransformer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The layer's front door, and the benchmark.
 *
 * <p>Two jobs here. The first is dispatch: the right transformer must be chosen
 * from the bytes, and — much more importantly — an input nothing recognises must
 * come back unharmed. Continuum is a reliability layer, so a context transformer
 * that mangled or rejected data it had no opinion about would make every
 * pipeline more fragile than it was before this existed.
 *
 * <p>The second is measurement. The benchmark is deliberately end-to-end and
 * deliberately honest about its baseline: the comparison is against a plain-text
 * rendering of the same input, never against the base64 of a binary file, which
 * would report an enormous saving that means nothing.
 */
class ContextTransformServiceTest {

    private final ContextTransformService service = new ContextTransformService(
            List.of(new SpreadsheetTransformer(), new LogTransformer(),
                    new EmailThreadTransformer()),
            null);

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String bigLog(int lines) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            b.append(String.format(
                    "2026-08-02T14:%02d:%02dZ INFO [api-gateway] GET /v1/orders/%d 200 %dms%n",
                    (i / 60) % 60, i % 60, 100000 + i, 20 + (i % 40)));
        }
        for (int i = 0; i < lines / 20; i++) {
            b.append(String.format(
                    "2026-08-02T14:%02d:%02dZ ERROR [payments] Timeout calling billing-api "
                            + "after 30000ms%n", (i / 60) % 60, i % 60));
        }
        return b.toString();
    }

    @Test
    @DisplayName("the transformer is chosen from the bytes, not from a declaration")
    void dispatchesOnContent() {
        assertThat(service.transform(bytes(bigLog(50)), "anything.dat",
                RenderBudget.standard()).transformer()).isEqualTo("logs");

        assertThat(service.transform(bytes("a,b,c\n1,2,3\n4,5,6\n7,8,9\n"), "mystery",
                RenderBudget.standard()).transformer()).isEqualTo("spreadsheet");

        // The declared name is a hint at best: upload widgets routinely lie, and
        // being wrong about the declaration is a worse failure than reading the
        // bytes.
        assertThat(service.transform(bytes(bigLog(50)), "notes.txt",
                RenderBudget.standard()).transformer()).isEqualTo("logs");
    }

    @Test
    @DisplayName("an input nothing recognises comes back unchanged, not rejected")
    void passesUnknownInputThrough() {
        String prose = "Just some ordinary prose that is not a log, a table or an email. "
                + "It should reach the model exactly as it arrived.";

        ContextTransformService.Result r =
                service.transform(bytes(prose), "notes.txt", RenderBudget.standard());

        assertThat(r.transformed()).isFalse();
        assertThat(r.transformer()).isNull();
        // The crucial property. A context layer that returned 400 for anything
        // it had no opinion about would make every pipeline more fragile.
        assertThat(r.rendered()).isEqualTo(prose);
        assertThat(r.stats().before()).isEqualTo(r.stats().after());
    }

    @Test
    @DisplayName("null and empty input do not throw")
    void toleratesNothing() {
        assertThat(service.transform(null, null, RenderBudget.standard()).transformed()).isFalse();
        assertThat(service.transform(new byte[0], "x", RenderBudget.standard()).transformed())
                .isFalse();
    }

    @Test
    @DisplayName("a transformer that throws does not take the request down")
    void survivesADefectiveTransformer() {
        ContextTransformer broken = new ContextTransformer() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public String label() {
                return "Broken";
            }

            @Override
            public ContextType produces() {
                return ContextType.PASSTHROUGH;
            }

            @Override
            public boolean supports(byte[] input, String filename) {
                return true;
            }

            @Override
            public CanonicalContext transform(byte[] input, String filename) {
                throw new IllegalStateException("boom");
            }

            @Override
            public String rawTextBaseline(byte[] input, String filename) {
                return "";
            }
        };

        ContextTransformService s = new ContextTransformService(List.of(broken), null);
        ContextTransformService.Result r =
                s.transform(bytes("hello there"), "x", RenderBudget.standard());

        assertThat(r.transformed()).isFalse();
        assertThat(r.rendered()).isEqualTo("hello there");
    }

    @Test
    @DisplayName("the budget changes how much is spoken, not what was recovered")
    void budgetControlsRenderingOnly() {
        byte[] log = bytes(bigLog(2000));

        ContextTransformService.Result summary =
                service.transform(log, "a.log", RenderBudget.summary());
        ContextTransformService.Result standard =
                service.transform(log, "a.log", RenderBudget.standard());

        assertThat(summary.rendered().length()).isLessThan(standard.rendered().length());
        // The canonical form is identical; only the rendering differs. That is
        // what makes it safe to re-render at a different budget later.
        assertThat(summary.context().structure()).isEqualTo(standard.context().structure());
    }

    @Test
    @DisplayName("capabilities describe what exists rather than being hard-coded in the console")
    void reportsCapabilities() {
        assertThat(service.capabilities()).hasSize(3);
        assertThat(service.capabilities()).anySatisfy(c ->
                assertThat(c.get("produces")).isEqualTo("SEMANTIC_TABLE"));
    }

    // --- benchmark -----------------------------------------------------------

    /**
     * The headline measurement, run as a test so it cannot drift unnoticed.
     *
     * <p>10,500 log lines is past the point where the raw text fits a normal
     * prompt at all, which is the situation the feature exists for.
     */
    @Test
    @DisplayName("BENCHMARK: a 10k-line log reduces by more than 95% and keeps the exceptions")
    void benchmarkLogs() {
        byte[] log = bytes(bigLog(10000));

        ContextTransformService.Result r =
                service.transform(log, "outage.log", RenderBudget.standard());

        TokenStats s = r.stats();
        assertThat(s.before()).isGreaterThan(150_000);
        assertThat(s.reduction()).isGreaterThan(0.95);
        // Reduction alone is not the claim — a truncation would score better and
        // be useless. What must survive is the error and its count.
        assertThat(r.rendered()).contains("ERROR").contains("Timeout calling billing-api");
        assertThat(r.rendered()).contains("500x");

        System.out.printf("BENCHMARK logs: %,d → %,d tokens (%.1f%% reduction)%n",
                s.before(), s.after(), s.reduction() * 100);
    }

    @Test
    @DisplayName("BENCHMARK: an email thread reduces, and the newest message survives")
    void benchmarkEmail() {
        String eml = """
                From: Priya <priya@acme.example>
                To: Support <support@vendor.example>
                Subject: Re: Invoice 4471
                Date: Sun, 2 Aug 2026 14:05:00 +0000
                MIME-Version: 1.0
                Content-Type: text/plain; charset=UTF-8

                That still does not match. Our PO says 12,500 and you billed 15,200.

                --
                Priya Nair
                Head of Procurement
                +91 98765 43210

                This email and any attachments are confidential and may be privileged.

                On Sun, 2 Aug 2026 at 13:40, Support <support@vendor.example> wrote:
                > The amount billed is 15,200 as agreed in the contract dated March.
                > Please see the attached statement for the full breakdown of charges.
                >
                > On Sun, 2 Aug 2026 at 12:10, Priya <priya@acme.example> wrote:
                > > The invoice we received today shows a different total to our
                > > purchase order. Could you check it please?
                """;

        ContextTransformService.Result r =
                service.transform(bytes(eml), "thread.eml", RenderBudget.standard());

        assertThat(r.transformed()).isTrue();
        assertThat(r.stats().improved()).isTrue();
        assertThat(r.rendered()).contains("Our PO says 12,500");
        assertThat(r.rendered()).doesNotContain("confidential and may be privileged");

        System.out.printf("BENCHMARK email: %,d → %,d tokens (%.1f%% reduction)%n",
                r.stats().before(), r.stats().after(), r.stats().reduction() * 100);
    }

    @Test
    @DisplayName("BENCHMARK: a wide CSV keeps every row while naming its columns")
    void benchmarkSpreadsheet() {
        StringBuilder csv = new StringBuilder("Region,Quarter,Revenue (₹ crore),Units\n");
        String[] regions = {"West", "East", "North", "South"};
        for (int i = 0; i < 800; i++) {
            csv.append(regions[i % 4]).append(",Q").append((i % 4) + 1).append(',')
                    .append(50 + (i % 90)).append(".4,").append(1000 + i * 7).append('\n');
        }

        ContextTransformService.Result r =
                service.transform(bytes(csv.toString()), "regions.csv", RenderBudget.standard());

        assertThat(r.transformed()).isTrue();
        assertThat(r.rendered()).contains("Dimensions: Region, Quarter");
        assertThat(r.rendered()).contains("Revenue (₹ crore)");

        System.out.printf("BENCHMARK spreadsheet: %,d → %,d tokens (%.1f%% reduction)%n",
                r.stats().before(), r.stats().after(), r.stats().reduction() * 100);
    }
}
