package io.continuum.compression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V8 LLMLingua-inspired prompt compression: real token reduction that preserves
 * salient spans, is deterministic, and never grows the input.
 */
class PromptCompressorTest {

    private final PromptCompressor compressor = new PromptCompressor();

    @Test
    void compressesVerboseTextAndKeepsItDeterministic() {
        String verbose = "Well, I was just basically thinking that we should probably really "
                + "consider, you know, actually maybe looking into the possibility of perhaps "
                + "migrating the authentication service, because honestly it is quite old and it "
                + "has been causing a lot of various different intermittent problems for the team. "
                + "The migration should improve reliability substantially and reduce the on-call "
                + "burden that the engineers have been repeatedly complaining about for months.";

        PromptCompressor.Result a = compressor.compress(verbose, 0.5, 30);
        PromptCompressor.Result b = compressor.compress(verbose, 0.5, 30);

        assertEquals(a.text(), b.text(), "compression is deterministic");
        assertTrue(a.compressedTokens() < a.originalTokens(), "must reduce token count");
        assertTrue(a.text().toLowerCase().contains("migrat")
                        || a.text().toLowerCase().contains("authentication"),
                "the core topic survives: " + a.text());
    }

    @Test
    void protectsNumbersIdsCodeAndQuotes() {
        String text = "The wire transfer of $4,500 to account 88231XYZ must be approved today. "
                + "The reference id is TXN-99A2B and the config was {\"limit\": 5000, \"currency\": \"USD\"}. "
                + "Contact the analyst at analyst@bank.com for the full breakdown of the review. "
                + "This is a fairly long sentence with lots of filler words so that compression "
                + "actually has something low-information to remove during the token pruning pass.";

        PromptCompressor.Result r = compressor.compress(text, 0.5, 30);

        assertTrue(r.protectedSpans() > 0, "salient spans were detected");
        assertTrue(r.text().contains("$4,500"), "money preserved");
        assertTrue(r.text().contains("88231XYZ"), "account id preserved");
        assertTrue(r.text().contains("TXN-99A2B"), "reference id preserved");
        assertTrue(r.text().contains("analyst@bank.com"), "email preserved");
        assertTrue(r.text().contains("5000"), "JSON numeric preserved");
    }

    @Test
    void shortInputPassesThroughUnchanged() {
        PromptCompressor.Result r = compressor.compress("Approve the loan.", 0.5, 30);
        assertEquals("Approve the loan.", r.text());
        assertEquals(r.originalTokens(), r.compressedTokens());
    }

    @Test
    void neverGrowsTheInput() {
        String text = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi "
                + "omicron pi rho sigma tau upsilon phi chi psi omega one two three four five six.";
        PromptCompressor.Result r = compressor.compress(text, 0.9, 10);
        assertTrue(r.compressedTokens() <= r.originalTokens());
    }

    @Test
    void lowerTargetRatioCompressesMore() {
        String verbose = ("The system should really probably be updated because it is quite old. ").repeat(6);
        PromptCompressor.Result loose = compressor.compress(verbose, 0.8, 20);
        PromptCompressor.Result tight = compressor.compress(verbose, 0.3, 20);
        assertTrue(tight.compressedTokens() <= loose.compressedTokens(),
                "a tighter target keeps fewer tokens");
    }
}
