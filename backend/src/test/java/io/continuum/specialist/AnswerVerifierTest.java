package io.continuum.specialist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verifier asks a different question from the confidence policy. The policy
 * is about the instruction; this is about whether the advice is anchored to what
 * was actually found. An answer can hedge beautifully and still describe an
 * injury nobody detected.
 */
class AnswerVerifierTest {

    private static ContextBuilder.Context ctx(SpecialistProvider.Finding... findings) {
        return ContextBuilder.build("triage", "what now?",
                List.of(new ContextBuilder.StepResult("detector", List.of(findings), 0, null)));
    }

    private static ContextBuilder.Context nothingFound() {
        return ContextBuilder.build("triage", "what now?",
                List.of(new ContextBuilder.StepResult("detector", List.of(), 0, null)));
    }

    private static ContextBuilder.Context nothingRan() {
        return ContextBuilder.build("triage", "what now?",
                List.of(new ContextBuilder.StepResult("detector", List.of(), 0, "refused")));
    }

    private static SpecialistProvider.Finding f(String label, double c) {
        return new SpecialistProvider.Finding(label, c, null);
    }

    private static AnswerVerifier.Result check(String answer, ContextBuilder.Context c) {
        return AnswerVerifier.check(answer, c, "what now?", 0.40);
    }

    // --- certainty beyond the evidence ---------------------------------------

    @Test
    @DisplayName("A confident diagnosis with nothing found fails")
    void certaintyWithoutFindingsFails() {
        AnswerVerifier.Result r = check(
                "This is a deep laceration. Apply pressure immediately.", nothingFound());

        assertThat(r.verdict()).isEqualTo(AnswerVerifier.Verdict.FAIL);
        assertThat(r.issues()).anySatisfy(i ->
                assertThat(i.kind()).isEqualTo("certainty-without-findings"));
    }

    @Test
    @DisplayName("A confident diagnosis during an outage fails, and says why")
    void certaintyWithoutAnalysisFails() {
        AnswerVerifier.Result r = check("The animal has a fractured leg.", nothingRan());

        assertThat(r.verdict()).isEqualTo(AnswerVerifier.Verdict.FAIL);
        assertThat(r.issues()).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo("certainty-without-analysis");
            assertThat(i.detail()).contains("nothing was examined");
        });
    }

    @Test
    @DisplayName("A confident diagnosis on weak evidence fails and names the number")
    void certaintyBeyondWeakEvidenceFails() {
        AnswerVerifier.Result r = check(
                "This is a fracture. Splint the leg.", ctx(f("fracture", 0.31)));

        assertThat(r.verdict()).isEqualTo(AnswerVerifier.Verdict.FAIL);
        assertThat(r.issues()).anySatisfy(i -> assertThat(i.detail()).contains("only 31%"));
    }

    @Test
    @DisplayName("The same certainty on strong evidence is fine")
    void certaintyOnStrongEvidencePasses() {
        // The check is about certainty exceeding the evidence, not about
        // certainty. A 91% detection earns a direct answer.
        AnswerVerifier.Result r = check(
                "This is an open wound. Apply firm pressure with a clean cloth.",
                ctx(f("open wound", 0.91)));

        assertThat(r.verdict()).isEqualTo(AnswerVerifier.Verdict.OK);
        assertThat(r.issues()).isEmpty();
    }

    @Test
    @DisplayName("'unconfirmed' is not read as 'confirmed'")
    void negatedFormIsNotAnAssertion() {
        // Found by driving a pipeline. The context builder writes "Weak signals,
        // treat as unconfirmed", the model echoed it, and a substring match on
        // the certainty marker "confirmed" failed the answer — the phrase
        // carrying the strongest available doubt was read as certainty.
        AnswerVerifier.Result r = check(
                "Weak signals, treat as unconfirmed. A fracture may be present.",
                ctx(f("fracture", 0.31)));

        assertThat(r.issues()).noneSatisfy(i ->
                assertThat(i.kind()).startsWith("certainty"));
        assertThat(r.verdict()).isNotEqualTo(AnswerVerifier.Verdict.FAIL);
    }

    @Test
    @DisplayName("Certainty markers still match as whole words")
    void wordBoundariesDoNotBreakRealMatches() {
        assertThat(check("The diagnosis is confirmed.", ctx(f("fracture", 0.31))).verdict())
                .isEqualTo(AnswerVerifier.Verdict.FAIL);
        // ...and are not fooled by a longer word that merely contains one.
        assertThat(check("Reconfirmed nothing here.", ctx(f("fracture", 0.31))).issues())
                .noneSatisfy(i -> assertThat(i.kind()).startsWith("certainty"));
    }

    // --- invented figures ------------------------------------------------------

    @Test
    @DisplayName("A confidence figure matching no finding is caught")
    void inventedPercentageFails() {
        AnswerVerifier.Result r = check(
                "I am about 95% certain this is significant.", ctx(f("open wound", 0.91)));

        assertThat(r.verdict()).isEqualTo(AnswerVerifier.Verdict.FAIL);
        assertThat(r.issues()).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo("invented-figure");
            assertThat(i.detail()).contains("95%");
        });
    }

    @Test
    @DisplayName("Restating a finding's own confidence is not an invention")
    void restatedPercentageIsFine() {
        AnswerVerifier.Result r = check(
                "The wound was detected with 91% confidence.", ctx(f("open wound", 0.91)));

        assertThat(r.issues()).noneSatisfy(i ->
                assertThat(i.kind()).isEqualTo("invented-figure"));
    }

    @Test
    @DisplayName("A number the user themselves used is theirs, not an invention")
    void userSuppliedNumberIsAllowed() {
        AnswerVerifier.Result r = AnswerVerifier.check(
                "You mentioned 50% — that is not something the analysis can confirm.",
                ctx(f("open wound", 0.91)), "is it 50% healed?", 0.40);

        assertThat(r.issues()).noneSatisfy(i ->
                assertThat(i.kind()).isEqualTo("invented-figure"));
    }

    // --- coverage ---------------------------------------------------------------

    @Test
    @DisplayName("A finding the advice never mentions is reported")
    void uncoveredFindingWarns() {
        AnswerVerifier.Result r = check(
                "Apply firm pressure to the wound with a clean cloth.",
                ctx(f("open wound", 0.91), f("bleeding", 0.67)));

        assertThat(r.covered()).contains("open wound");
        assertThat(r.uncovered()).contains("bleeding");
    }

    @Test
    @DisplayName("Coverage can only ever warn, never fail")
    void coverageNeverFails() {
        // The match is lexical, so a model writing "laceration" for a finding
        // labelled "open wound" reads as uncovered while having covered it
        // perfectly. Failing an answer for that would punish good writing.
        AnswerVerifier.Result r = check("Seek veterinary attention promptly.",
                ctx(f("open wound", 0.91), f("bleeding", 0.67)));

        assertThat(r.verdict()).isEqualTo(AnswerVerifier.Verdict.WARN);
        assertThat(r.issues()).allSatisfy(i -> assertThat(i.severe()).isFalse());
    }

    @Test
    @DisplayName("The head word covers a multi-word label")
    void headWordCounts() {
        AnswerVerifier.Result r = check("Clean the wound carefully.", ctx(f("open wound", 0.91)));

        assertThat(r.covered()).contains("open wound");
        assertThat(r.uncovered()).isEmpty();
    }

    // --- verdicts and enforcement ------------------------------------------------

    @Test
    @DisplayName("An answer that covers everything and asserts nothing beyond it is OK")
    void cleanAnswerPasses() {
        AnswerVerifier.Result r = check(
                "There appears to be an open wound with some bleeding. Apply gentle pressure.",
                ctx(f("open wound", 0.91), f("bleeding", 0.67)));

        assertThat(r.verdict()).isEqualTo(AnswerVerifier.Verdict.OK);
    }

    @Test
    @DisplayName("The replacement states the evidence rather than hiding it")
    void replacementCarriesTheFindings() {
        String msg = AnswerVerifier.replacement(ctx(f("open wound", 0.91)));

        assertThat(msg).contains("open wound").contains("91%");
    }

    @Test
    @DisplayName("The replacement for an outage does not read as an all-clear")
    void replacementForOutageIsHonest() {
        assertThat(AnswerVerifier.replacement(nothingRan()))
                .contains("did not complete")
                .doesNotContain("nothing was found");
    }

    @Test
    @DisplayName("The method is reported as lexical")
    void methodIsDisclosed() {
        assertThat(check("anything", ctx(f("wound", 0.9))).method()).isEqualTo("lexical");
    }

    @Test
    @DisplayName("An empty answer does not crash the check")
    void emptyAnswerIsHandled() {
        assertThat(check(null, ctx(f("wound", 0.9))).verdict())
                .isNotEqualTo(AnswerVerifier.Verdict.OK);
        assertThat(check("", nothingFound()).verdict()).isEqualTo(AnswerVerifier.Verdict.OK);
    }
}
