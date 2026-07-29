package io.continuum.specialist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The context builder is where the specialist layer either works or quietly
 * fails. Everything upstream can be correct — the right detector, the right
 * threshold, the right findings — and the answer will still be wrong if the
 * prompt lets the model believe it examined the input itself.
 *
 * <p>So these tests are less about formatting than about what the model is
 * permitted to conclude.
 */
class ContextBuilderTest {

    private static SpecialistProvider.Finding f(String label, double confidence) {
        return new SpecialistProvider.Finding(label, confidence, null);
    }

    private static ContextBuilder.StepResult ok(String name, SpecialistProvider.Finding... findings) {
        return ContextBuilder.StepResult.ofFindings(name, List.of(findings), 0, null);
    }

    // --- the central guarantee ----------------------------------------------

    @Test
    @DisplayName("The model is always told it has not seen the raw input")
    void alwaysStatesTheModelCannotSeeTheInput() {
        String withFindings = ContextBuilder.build("triage", "what do I do?",
                List.of(ok("detector", f("open wound", 0.91)))).prompt();
        String withoutFindings = ContextBuilder.build("triage", "what do I do?",
                List.of(ok("detector"))).prompt();

        // Both branches, because the empty one is where a model is most tempted
        // to fill the silence from its own priors.
        assertThat(withFindings).contains("NOT been shown the raw input");
        assertThat(withoutFindings).contains("NOT been shown the raw input");
    }

    @Test
    @DisplayName("Nothing found is stated explicitly, not left as a gap")
    void absenceIsStated() {
        ContextBuilder.Context ctx = ContextBuilder.build("triage", "is the animal hurt?",
                List.of(ok("injury detector")));

        assertThat(ctx.anythingFound()).isFalse();
        assertThat(ctx.totalFindings()).isZero();
        assertThat(ctx.prompt())
                .contains("No findings")
                .contains("Do not describe or diagnose anything the analysis did not report");
        // The question still reaches the model — it should answer "I'd need a
        // clearer photo", not nothing at all.
        assertThat(ctx.prompt()).contains("is the animal hurt?");
    }

    @Test
    @DisplayName("Findings below threshold are counted, not silently forgotten")
    void withheldSignalsAreCounted() {
        // "nothing found" and "nothing confident enough" are different
        // situations and the model should be able to tell them apart.
        String prompt = ContextBuilder.build("triage", null,
                List.of(new ContextBuilder.StepResult("detector", List.of(), 3, null))).prompt();

        assertThat(prompt).contains("3 weak signals were discarded");
    }

    // --- confidence bands ----------------------------------------------------

    @Test
    @DisplayName("Confidence is stated as a number and grouped by strength")
    void confidenceIsGraded() {
        ContextBuilder.Context ctx = ContextBuilder.build("triage", null, List.of(ok("detector",
                f("open wound", 0.91), f("bruising", 0.52), f("fracture", 0.22))));

        String p = ctx.prompt();
        assertThat(p).contains("Observed:").contains("open wound (91% confident");
        assertThat(p).contains("Possible, less certain:").contains("bruising (52% confident");
        assertThat(p).contains("Weak signals, treat as unconfirmed:").contains("fracture (22% confident");
        assertThat(p).contains("Let your certainty follow the confidence figures");

        assertThat(ctx.topConfidence()).isEqualTo(0.91);
        assertThat(ctx.totalFindings()).isEqualTo(3);
    }

    @Test
    @DisplayName("A lone weak finding does not get promoted to an observation")
    void weakFindingAloneIsNotPresentedAsObserved() {
        // The failure this guards against: one 0.31 detection, no strong ones,
        // and a header that reads like the detector was sure.
        String p = ContextBuilder.build("triage", null,
                List.of(ok("detector", f("fracture", 0.31)))).prompt();

        assertThat(p).doesNotContain("Observed:");
        assertThat(p).contains("fracture (31% confident");
    }

    // --- partial evidence ----------------------------------------------------

    @Test
    @DisplayName("A specialist that failed is named, so the evidence is known to be partial")
    void failureIsDisclosedToTheModel() {
        ContextBuilder.Context ctx = ContextBuilder.build("triage", null, List.of(
                ok("injury detector", f("open wound", 0.88)),
                new ContextBuilder.StepResult("breed classifier", List.of(), 0, "timeout")));

        assertThat(ctx.prompt())
                .contains("breed classifier did not respond")
                .contains("analysis may be incomplete");
        // The working specialist still contributed.
        assertThat(ctx.totalFindings()).isEqualTo(1);
        assertThat(ctx.anythingFound()).isTrue();
    }

    @Test
    @DisplayName("Every specialist failing is not reported as 'nothing found'")
    void totalFailureIsNotDisguisedAsACleanResult() {
        // Found while driving the pipeline live. With the endpoint down, the
        // context told the model the analysis "did not identify anything above
        // the configured confidence threshold" — which asserts that something
        // looked. Nothing looked. An application reading that as "all clear"
        // reports a healthy animal during an outage.
        ContextBuilder.Context ctx = ContextBuilder.build("triage", "is she hurt?", List.of(
                new ContextBuilder.StepResult("injury detector", List.of(), 0, "connection refused")));

        assertThat(ctx.analysisRan()).isFalse();
        assertThat(ctx.prompt())
                .contains("No analysis is available")
                .contains("nothing has been examined")
                .contains("no analysis was performed at all")
                .doesNotContain("above the configured confidence threshold");
    }

    @Test
    @DisplayName("One of two failing is incomplete, not unavailable")
    void partialFailureIsDistinctFromTotalFailure() {
        ContextBuilder.Context ctx = ContextBuilder.build("triage", null, List.of(
                ok("injury detector"),
                new ContextBuilder.StepResult("breed classifier", List.of(), 0, "timeout")));

        // One specialist did run and reported nothing — that is evidence.
        assertThat(ctx.analysisRan()).isTrue();
        assertThat(ctx.prompt())
                .contains("above the configured confidence threshold")
                .contains("analysis may be incomplete")
                .doesNotContain("no analysis was performed");
    }

    @Test
    @DisplayName("A pipeline with no steps at all still reads as no analysis, not a clean result")
    void emptyPipelineDoesNotClaimAnalysisRan() {
        ContextBuilder.Context ctx = ContextBuilder.build("triage", null, List.of());

        // No steps means nothing was checked. analysisRan stays true here only
        // because there was nothing to fail; the prompt must not imply a check.
        assertThat(ctx.anythingFound()).isFalse();
        assertThat(ctx.prompt()).contains("NOT been shown the raw input");
    }

    @Test
    @DisplayName("A successful run reports that analysis ran")
    void successReportsAnalysisRan() {
        assertThat(ContextBuilder.build("triage", null,
                List.of(ok("detector", f("open wound", 0.91)))).analysisRan()).isTrue();
    }

    @Test
    @DisplayName("A failed specialist contributes no findings and no confidence")
    void failureContributesNothing() {
        ContextBuilder.Context ctx = ContextBuilder.build("triage", null, List.of(
                ContextBuilder.StepResult.ofFindings("detector", List.of(f("wound", 0.99)), 0,
                        "connection refused")));

        // The findings list on an errored step is not evidence — an adapter may
        // have populated it from a partial body. Trusting it would let a failure
        // masquerade as a 99% confident observation.
        assertThat(ctx.totalFindings()).isZero();
        assertThat(ctx.topConfidence()).isZero();
        assertThat(ctx.anythingFound()).isFalse();
    }

    // --- the structured mirror ------------------------------------------------

    @Test
    @DisplayName("The same facts come back as data, sourced per specialist")
    void structuredMirrorsThePrompt() {
        ContextBuilder.Context ctx = ContextBuilder.build("triage", "help", List.of(
                ok("injury detector", f("open wound", 0.91)),
                ok("breed classifier", f("labrador", 0.77))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) ctx.structured().get("findings");
        assertThat(findings).hasSize(2);
        assertThat(findings.get(0)).containsEntry("source", "injury detector")
                .containsEntry("label", "open wound").containsEntry("confidence", 0.91);
        assertThat(findings.get(1)).containsEntry("source", "breed classifier");
        assertThat(ctx.structured()).containsEntry("userPrompt", "help");
    }

    @Test
    @DisplayName("The user's own question survives verbatim")
    void userPromptIsCarried() {
        String p = ContextBuilder.build("triage", "  Should I take her to a vet tonight?  ",
                List.of(ok("detector", f("open wound", 0.9)))).prompt();

        assertThat(p).contains("The user asks: Should I take her to a vet tonight?");
    }

    @Test
    @DisplayName("No question, no invented one")
    void missingUserPromptIsFine() {
        String p = ContextBuilder.build("inspection", null,
                List.of(ok("detector", f("crack", 0.8)))).prompt();

        assertThat(p).doesNotContain("The user asks");
        assertThat(p).contains("Automated analysis of the supplied inspection input");
    }
}
