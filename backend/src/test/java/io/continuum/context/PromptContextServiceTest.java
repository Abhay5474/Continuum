package io.continuum.context;

import io.continuum.context.transform.EmailThreadTransformer;
import io.continuum.context.transform.LogTransformer;
import io.continuum.context.transform.SpreadsheetTransformer;
import io.continuum.persistence.entity.DeveloperAuthEntity;
import io.continuum.persistence.repository.ContextTransformRepository;
import io.continuum.persistence.repository.DeveloperAuthRepository;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The chat path's context layer.
 *
 * <p>Two obligations, and the second matters more than the first. It has to
 * transform data a caller pasted into a message — and it has to leave prose
 * completely alone, because mistaking a paragraph for a table destroys the
 * question the caller actually asked.
 */
class PromptContextServiceTest {

    private static final String DEV = "dev_1";

    private PromptContextService service;
    private DeveloperAuthEntity auth;

    @BeforeEach
    void setUp() {
        ContextTransformRepository ctxRepo = mock(ContextTransformRepository.class);
        when(ctxRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        ContextTransformService transforms = new ContextTransformService(
                List.of(new SpreadsheetTransformer(), new LogTransformer(), new EmailThreadTransformer()),
                ctxRepo);

        auth = new DeveloperAuthEntity(DEV, "hash");
        auth.setContextTransformEnabled(true);
        DeveloperAuthRepository devAuth = mock(DeveloperAuthRepository.class);
        when(devAuth.findById(DEV)).thenReturn(Optional.of(auth));
        when(devAuth.save(any())).thenAnswer(i -> i.getArgument(0));

        service = new PromptContextService(transforms, devAuth);
    }

    private static LlmRequest userSaying(String content) {
        return new LlmRequest("mock-small", List.of(Message.user(content)), 512, 0.2);
    }

    private static String contentOf(LlmRequest r) {
        return r.messages().get(0).content();
    }

    /** A CSV big enough to clear the size floor, with a real aggregate row. */
    private static String csv() {
        StringBuilder b = new StringBuilder("Region,Revenue,Units,Quarter,Owner\n");
        for (int i = 0; i < 24; i++) {
            b.append("Region-").append(i).append(',').append(1000 + i * 37)
                    .append(',').append(50 + i).append(",Q3,owner-").append(i).append('\n');
        }
        return b.toString();
    }

    private static String logs() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            b.append("2026-08-02T14:0").append(i % 6).append(":00Z INFO [api] GET /v1/orders/")
                    .append(1000 + i).append(" 200 ").append(20 + i).append("ms\n");
        }
        for (int i = 0; i < 12; i++) {
            b.append("2026-08-02T14:07:00Z ERROR [payments] Timeout calling billing-api after 30000ms\n");
        }
        return b.toString();
    }

    // --- the feature ---------------------------------------------------------

    @Test
    @DisplayName("a message that is entirely a CSV reaches the model as a semantic table")
    void transformsAWholeMessage() {
        LlmRequest out = service.maybeTransform(DEV, userSaying(csv()));
        String content = contentOf(out);

        assertThat(content).contains("TABLE");
        assertThat(content).contains("Dimensions:");
        // The measures are what a flat CSV dump cannot say.
        assertThat(content).contains("Measures:");
        assertThat(content).doesNotContain("Region-7,1259");
    }

    @Test
    @DisplayName("a fenced block is transformed and the prose around it is preserved byte for byte")
    void transformsAFencedBlockAndKeepsTheQuestion() {
        String before = "Here is last quarter's export.\n\n";
        String after = "\n\nWhich region led on revenue, and why?";
        LlmRequest out = service.maybeTransform(DEV, userSaying(before + "```csv\n" + csv() + "```" + after));
        String content = contentOf(out);

        assertThat(content).startsWith(before);
        assertThat(content).endsWith(after);
        assertThat(content).contains("TABLE");
        assertThat(content).doesNotContain("```");
    }

    @Test
    @DisplayName("logs collapse to an incident, not forty near-identical lines")
    void transformsLogs() {
        LlmRequest out = service.maybeTransform(DEV, userSaying(logs()));
        String content = contentOf(out);

        assertThat(content).isNotEqualTo(logs());
        // The saving is the whole claim: it must actually be smaller.
        assertThat(content.length()).isLessThan(logs().length());
    }

    // --- the thing that must not happen --------------------------------------

    @Test
    @DisplayName("prose with commas is NOT mistaken for a table")
    void leavesProseAlone() {
        // Every line has exactly one comma, which is all the spreadsheet
        // detector asks for. This is the input that makes paragraph-scanning
        // unsafe, and the reason only whole messages and fences are considered.
        String prose = """
                Hello, I have a question about the billing behaviour.
                Yes, that is the endpoint I meant.
                No, it is not returning what I expected.
                Earlier, I tried the same call with a different key.
                Anyway, could you explain what the retry policy does here?
                """.repeat(4);

        LlmRequest in = userSaying(prose);
        assertThat(contentOf(service.maybeTransform(DEV, in))).isEqualTo(prose);
    }

    @Test
    @DisplayName("a short message is never touched, however comma-shaped")
    void leavesShortMessagesAlone() {
        String shortCsv = "a,b\n1,2\n3,4\n";
        LlmRequest in = userSaying(shortCsv);
        assertThat(contentOf(service.maybeTransform(DEV, in))).isEqualTo(shortCsv);
    }

    @Test
    @DisplayName("a system message is left alone even when it is a recognised table")
    void leavesSystemMessagesAlone() {
        LlmRequest in = new LlmRequest("mock-small",
                List.of(Message.system(csv()), Message.user("summarise it")), 512, 0.2);
        LlmRequest out = service.maybeTransform(DEV, in);

        assertThat(out.messages().get(0).content()).isEqualTo(csv());
    }

    @Test
    @DisplayName("a table is transformed even though the canonical form is larger")
    void acceptsAModestGrowthBecauseStructureIsThePoint() {
        // Measured on this fixture: 183 tokens in, 354 out. The growth buys the
        // dimension/measure split and the unresolved-unit notes, which is the
        // reason to do this at all. A rule that declined every transform that
        // grew would have switched the feature off for spreadsheets while
        // appearing to work.
        String content = contentOf(service.maybeTransform(DEV, userSaying(csv())));
        assertThat(content).contains("Dimensions:");
        assertThat(content.length()).isGreaterThan(csv().length());
    }

    @Test
    @DisplayName("a wide, shallow table is declined — the header costs more than the data")
    void declinesWhenTheStructureOutweighsTheData() {
        // Thirty columns, six rows: an ordinary wide export, and the shape that
        // actually runs away. Measured at 195 tokens in, 1312 out — every column
        // earns a header line and every unitless numeric column an unresolved
        // note, which six rows of data cannot carry. Tall tables are fine and
        // get transformed; this is the one that is not worth it.
        StringBuilder b = new StringBuilder();
        for (int c = 0; c < 30; c++) {
            b.append(c > 0 ? "," : "").append("col").append(c);
        }
        b.append('\n');
        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 30; c++) {
                b.append(c > 0 ? "," : "").append(r * 30 + c);
            }
            b.append('\n');
        }
        String wide = b.toString();

        assertThat(contentOf(service.maybeTransform(DEV, userSaying(wide)))).isEqualTo(wide);
    }

    // --- the guarantee -------------------------------------------------------

    @Test
    @DisplayName("off by default: nothing is inspected and the request is returned identical")
    void passesThroughWhenDisabled() {
        auth.setContextTransformEnabled(false);
        LlmRequest in = userSaying(csv());

        // Same instance, not merely equal: when off this must not even rebuild
        // the request, so no downstream stage can observe a difference.
        assertThat(service.maybeTransform(DEV, in)).isSameAs(in);
    }

    @Test
    @DisplayName("an unknown developer is treated as opted out")
    void unknownDeveloperIsOff() {
        LlmRequest in = userSaying(csv());
        assertThat(service.maybeTransform("nobody", in)).isSameAs(in);
    }

    @Test
    @DisplayName("nothing recognised means the very same request object goes on")
    void unrecognisedInputIsUntouched() {
        LlmRequest in = userSaying("Write me a haiku about distributed systems.");
        assertThat(service.maybeTransform(DEV, in)).isSameAs(in);
    }

    @Test
    @DisplayName("null and empty requests do not throw")
    void survivesDegenerateInput() {
        assertThat(service.maybeTransform(DEV, null)).isNull();
        LlmRequest empty = new LlmRequest("m", List.of(), 1, 0.0);
        assertThat(service.maybeTransform(DEV, empty)).isSameAs(empty);
    }

    @Test
    @DisplayName("status says which paths are live, because one toggle does not cover both")
    void statusDistinguishesThePaths() {
        assertThat(service.status(DEV))
                .containsEntry("gatewayEnabled", true)
                .containsEntry("pipelineAlwaysOn", true);

        auth.setContextTransformEnabled(false);
        assertThat(service.status(DEV))
                .containsEntry("gatewayEnabled", false)
                .containsEntry("pipelineAlwaysOn", true);
    }
}
