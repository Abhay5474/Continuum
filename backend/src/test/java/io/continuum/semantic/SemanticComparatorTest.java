package io.continuum.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SemanticComparatorTest {

    private final SemanticComparator comparator = new SemanticComparator(new ObjectMapper());
    private final ReplayVerificationPolicy policy = ReplayVerificationPolicy.defaults();

    @Test
    void rewordedSameDecisionPasses() {
        var r = comparator.compare(
                "The customer is high risk based on payment history.",
                "The customer has elevated risk given their payment record.",
                policy);
        assertTrue(r.passed(), "reworded but same decision should pass: " + r.explanation());
        assertTrue(r.intentScore() >= 0.5);
    }

    @Test
    void reversedDecisionFails() {
        var r = comparator.compare("Reject the transaction.", "Approve the transaction.", policy);
        assertFalse(r.passed(), "reversed decision must fail: " + r.explanation());
        assertEquals(0.0, r.intentScore(), 1e-9);
        assertTrue(r.explanation().contains("INTENT REVERSAL"));
    }

    @Test
    void structuredOutputTypeChangeReducesScore() {
        var same = comparator.compare("{\"risk\":\"high\",\"score\":0.9}", "{\"risk\":\"high\",\"score\":0.8}", policy);
        var typeChanged = comparator.compare("{\"risk\":\"high\",\"score\":0.9}", "{\"risk\":\"high\",\"score\":\"high\"}", policy);
        assertTrue(same.structuredCompatibilityScore() > typeChanged.structuredCompatibilityScore());
    }

    @Test
    void identicalOutputIsPerfect() {
        var r = comparator.compare("Customer approved for tier PRO.", "Customer approved for tier PRO.", policy);
        assertEquals(1.0, r.similarityScore(), 1e-9);
        assertTrue(r.passed());
    }
}
