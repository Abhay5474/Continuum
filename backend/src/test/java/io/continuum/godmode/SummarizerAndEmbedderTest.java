package io.continuum.godmode;

import io.continuum.godmode.memory.Summarizer;
import io.continuum.godmode.memory.TextEmbedder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The memory-compression primitives: deterministic extractive summarization
 * (real compression, no LLM) and hashing-trick embeddings for dedup/graph edges.
 */
class SummarizerAndEmbedderTest {

    private final Summarizer summarizer = new Summarizer();
    private final TextEmbedder embedder = new TextEmbedder();

    @Test
    void longInputIsCompressedDeterministically() {
        List<String> items = List.of(
                "The deployment pipeline failed because the database migration timed out during peak traffic hours.",
                "Engineers investigated the migration timeout and found a missing index on the events table.",
                "Adding the missing index fixed the migration timeout and the deployment pipeline recovered.",
                "Unrelated: the office coffee machine was replaced on Tuesday afternoon.",
                "A retrospective concluded that migrations should always be tested against production-sized data.",
                "The team also agreed to add index checks to the migration linter going forward.",
                "Someone mentioned the weather was nice.",
                "Migration timeout alerts were added to the observability dashboard for early detection.");

        Summarizer.Summary first = summarizer.summarize(items);
        Summarizer.Summary second = summarizer.summarize(items);

        assertEquals(first.text(), second.text(), "summarization is deterministic");
        assertTrue(first.summaryTokens() < first.inputTokens(), "summary must compress the input");
        assertTrue(first.text().toLowerCase().contains("migration"),
                "central topic survives compression");
    }

    @Test
    void shortInputPassesThroughUncompressed() {
        Summarizer.Summary s = summarizer.summarize(List.of("One fact.", "Another fact."));
        assertTrue(s.text().contains("One fact."));
        assertEquals(s.inputTokens(), s.summaryTokens());
    }

    @Test
    void embeddingsSeparateTopicsAndDetectNearDuplicates() {
        double[] a = embedder.embed("database migration timeout on the events table index");
        double[] b = embedder.embed("the events table migration timed out due to a missing database index");
        double[] c = embedder.embed("the quarterly marketing budget review meeting agenda");

        double dupSim = TextEmbedder.cosine(a, b);
        double diffSim = TextEmbedder.cosine(a, c);
        assertTrue(dupSim > diffSim, "related texts must score higher than unrelated");
        assertTrue(dupSim > 0.5, "near-duplicates should be clearly similar");
        assertEquals(1.0, TextEmbedder.cosine(a, a), 1e-9, "self-similarity is 1 (normalized)");
    }
}
