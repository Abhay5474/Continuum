package io.continuum.semantic;

/**
 * The multi-dimensional outcome of comparing a historical AI output against a
 * freshly generated one. A score of -1 for a dimension means "not applicable"
 * (e.g. no structured output to compare) and it is excluded from the overall.
 */
public record SemanticComparisonResult(
        double similarityScore,            // cosine over term-frequency vectors
        double intentScore,                // decision-polarity agreement
        double toolConsistencyScore,       // tool-selection agreement
        double structuredCompatibilityScore, // JSON shape/type compatibility
        double constraintScore,            // salient (numeric/id) token preservation
        double overallScore,
        boolean passed,
        String method,                     // "lexical" | "lexical+llm-judge"
        String explanation) {
}
