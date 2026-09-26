package io.continuum.dag.activities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DagSolverActivityTest {

    @Test
    void conclusionIsTheLastRealSentenceNotTheConfidenceLinesFullStop() {
        assertEquals("Assessment: LOW RISK.",
                DagSolverActivity.conclusionOf("Analysis of: \"x\". Assessment: LOW RISK. Confidence: 0.82."));
        assertEquals("So the sum is even.",
                DagSolverActivity.conclusionOf("Odd + odd = 2k+1 + 2m+1 = 2(k+m+1).\nSo the sum is even.\nConfidence: 0.91"));
        assertEquals("Splint the leg and see a vet.",
                DagSolverActivity.conclusionOf("Reasoning...\nConclusion: Splint the leg and see a vet.\n\nconfidence = .7!"));
    }
}
