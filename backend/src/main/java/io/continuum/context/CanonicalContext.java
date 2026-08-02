package io.continuum.context;

import java.util.List;
import java.util.Map;

/**
 * Application data, turned into something a language model can reason over.
 *
 * <p>The distinction from a Specialist is the whole point of this layer. A
 * Specialist calls somebody else's model with the developer's key and turns the
 * answer into evidence — Continuum does not do the work. A context transformer
 * <em>is</em> the work: deterministic, in process, no API key, no model, no
 * network. Given the same bytes it produces the same output forever.
 *
 * <p>Four obligations, and each exists because of a specific way this kind of
 * layer goes wrong.
 *
 * <ul>
 *   <li>{@link #render} — what the model is told. Budgeted, because the useful
 *       rendering of a workbook is not the complete one.</li>
 *   <li>{@link #describe} — what a program gets. The full structure, so a
 *       developer can build on the transformation rather than re-parse the
 *       prose we generated.</li>
 *   <li>{@link #ambiguities} — what could not be determined. Never guessed.</li>
 *   <li>{@link #provenance} — where values came from. Without this the layer has
 *       replaced the developer's data with something unverifiable.</li>
 * </ul>
 */
public interface CanonicalContext {

    ContextType type();

    /** What the caller supplied, for provenance and for the console. */
    String sourceName();

    /**
     * The rendering handed to a language model.
     *
     * <p>Must be deterministic and must respect the budget. Where content is
     * dropped to fit, the rendering has to say so — a model shown a truncated
     * table with no note will answer as though it saw all of it.
     */
    String render(RenderBudget budget);

    /** The complete structure, for programs rather than prompts. */
    Map<String, Object> describe();

    /** What could not be determined. Empty is a real and common answer. */
    List<Ambiguity> ambiguities();

    /**
     * Sources for the values worth citing.
     *
     * <p>Not every cell — that would be larger than the data. Headers,
     * measures, exceptions and messages: the things a person would want to look
     * up.
     */
    List<SourceRef> provenance();

    /**
     * What structure was recovered, as counts, for the console.
     *
     * <p>"4 sheets, 7 tables, 23 columns, 6 units" is the evidence that the
     * transformation did something. Free-form so each type reports what it
     * actually has rather than padding a common shape with zeroes.
     */
    Map<String, Object> structure();
}
