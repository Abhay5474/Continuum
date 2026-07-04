package io.continuum.autopilot.engine;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Memory-as-Action policy: Thompson sampling over Beta posteriors (the V4
 * machinery), with context pressure steering the summarize/defer trade-off.
 */
class MemActEngineTest {

    @Test
    void fullContextStronglyFavorsSummarization() {
        MemActEngine engine = new MemActEngine(new Random(42));
        int summarizeVotes = 0;
        for (int i = 0; i < 200; i++) {
            if (engine.shouldSummarize(0.95)) {
                summarizeVotes++;
            }
        }
        assertTrue(summarizeVotes > 150, "a nearly-full context must almost always summarize, got "
                + summarizeVotes + "/200");
    }

    @Test
    void repeatedFailureLearnsToPreferDeferring() {
        MemActEngine engine = new MemActEngine(new Random(7));
        // Summarization keeps producing no value; deferring keeps being right.
        for (int i = 0; i < 60; i++) {
            engine.observe(MemActEngine.MemoryAction.SUMMARIZE_NOW, 0.0);
            engine.observe(MemActEngine.MemoryAction.DEFER, 1.0);
        }
        int summarizeVotes = 0;
        for (int i = 0; i < 200; i++) {
            if (engine.shouldSummarize(0.2)) {
                summarizeVotes++;
            }
        }
        assertTrue(summarizeVotes < 80, "the bandit must learn from repeated zero-reward summaries, got "
                + summarizeVotes + "/200");
    }

    @Test
    void snapshotExposesPosteriorsForEveryAction() {
        MemActEngine engine = new MemActEngine(new Random(1));
        engine.observe(MemActEngine.MemoryAction.PRUNE_DUPLICATES, 1.0);
        var snap = engine.stateSnapshot();
        assertEquals(MemActEngine.MemoryAction.values().length, snap.size());
        @SuppressWarnings("unchecked")
        var prune = (java.util.Map<String, Object>) snap.get("PRUNE_DUPLICATES");
        assertEquals(1L, prune.get("successes"));
    }
}
