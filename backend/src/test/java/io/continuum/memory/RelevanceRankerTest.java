package io.continuum.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RelevanceRankerTest {

    private final RelevanceRanker ranker = new RelevanceRanker();

    @Test
    void relevantMemoryRanksAboveIrrelevant() {
        var ranked = ranker.rank("payment fraud risk assessment", List.of(
                new RelevanceRanker.Candidate(1, "Customer flagged for payment fraud risk last quarter", 0.5, 0),
                new RelevanceRanker.Candidate(2, "The weather today is sunny and warm", 0.5, 0)));
        assertEquals(1, ranked.get(0).id(), "topically relevant memory must rank first");
        assertTrue(ranked.get(0).relevance() > ranked.get(1).relevance());
    }

    @Test
    void fresherMemoryWinsWhenEquallyRelevant() {
        long day = 24L * 3600 * 1000;
        var ranked = ranker.rank("quarterly revenue report", List.of(
                new RelevanceRanker.Candidate(1, "quarterly revenue report figures", 0.5, 10L * day),
                new RelevanceRanker.Candidate(2, "quarterly revenue report figures", 0.5, 0)));
        assertEquals(2, ranked.get(0).id(), "fresher memory should rank higher when relevance ties");
    }

    @Test
    void salienceBreaksTies() {
        var ranked = ranker.rank("note", List.of(
                new RelevanceRanker.Candidate(1, "note", 0.1, 0),
                new RelevanceRanker.Candidate(2, "note", 0.9, 0)));
        assertEquals(2, ranked.get(0).id(), "higher salience should win when relevance and recency tie");
    }
}
