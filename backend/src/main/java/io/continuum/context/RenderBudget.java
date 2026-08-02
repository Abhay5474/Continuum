package io.continuum.context;

/**
 * How much room the canonical rendering may take.
 *
 * <p>A transformation is not one output. The same workbook is worth 40,000
 * tokens if the model must answer about any cell, 6,000 if it needs the shape
 * and the totals, and 800 if it only needs to know what the file contains. The
 * canonical form holds everything; the budget decides how much of it is spoken.
 *
 * @param maxTokens        the ceiling. Rendering stops before this and says so
 * @param includeProvenance whether cell-level sources are printed inline. Off by
 *                         default: printing a source for every value can cost
 *                         more than the values, and the machine-readable form
 *                         always keeps them regardless
 */
public record RenderBudget(int maxTokens, boolean includeProvenance) {

    /** Enough for real reasoning over a mid-sized input. */
    public static RenderBudget standard() {
        return new RenderBudget(8000, false);
    }

    /** Shape and summary only — for routing decisions and previews. */
    public static RenderBudget summary() {
        return new RenderBudget(900, false);
    }

    /** Everything, with sources, for an audit view rather than a prompt. */
    public static RenderBudget full() {
        return new RenderBudget(60000, true);
    }

    /**
     * The budget a caller named.
     *
     * <p>Here rather than in a controller because it is a property of the
     * budget: two entry points asking for "summary" must mean the same thing,
     * and an unknown name has to fall back somewhere sensible rather than fail
     * a transformation that would otherwise have worked.
     */
    public static RenderBudget named(String name) {
        return switch (name == null ? "" : name.toLowerCase(java.util.Locale.ROOT).strip()) {
            case "summary" -> summary();
            case "full" -> full();
            default -> standard();
        };
    }

    public RenderBudget withProvenance() {
        return new RenderBudget(maxTokens, true);
    }

    /** Characters this budget allows, using the same 4:1 estimate as elsewhere. */
    public int maxChars() {
        return Math.max(200, maxTokens * 4);
    }
}
