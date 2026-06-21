package io.continuum.aichaos;

/**
 * AI-native failure modes — the ways AI systems break that traditional workflow
 * engines never model. Each maps to a concrete injector.
 */
public enum AiFailureType {
    /** Output replaced with a plausible but wrong answer (and a reversed decision). */
    HALLUCINATION,
    /** Structured (JSON) output mangled: missing fields, wrong types, malformed. */
    SCHEMA_CORRUPTION,
    /** Model "selects" the wrong / dangerous tool. */
    TOOL_CORRUPTION,
    /** Malicious instructions injected into the prompt context. */
    PROMPT_INJECTION,
    /** Critical context messages removed before the call. */
    CONTEXT_TRUNCATION,
    /** Retrieved memory corrupted before use. */
    MEMORY_CORRUPTION,
    /** Output reworded to simulate a model version/behavior change (semantic drift). */
    PROVIDER_DRIFT
}
