package io.continuum.compression;

import io.continuum.provider.model.Role;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * How hard to compress each part of a prompt, instead of one ratio for all of it.
 *
 * <p>{@link PromptCompressor} applies a single target ratio to every message it
 * touches. LLMLingua (Jiang et al., EMNLP 2023) is explicit that this is the
 * wrong shape: the paper's <em>budget controller</em> allocates different ratios
 * to different regions of the prompt, because they do not carry information at
 * the same density. Its measured allocation is roughly
 *
 * <table>
 *   <tr><td>instructions</td><td>drop 10–20%</td></tr>
 *   <tr><td>demonstrations</td><td>drop 60–80%</td></tr>
 *   <tr><td>the question</td><td>drop 0–10%</td></tr>
 * </table>
 *
 * <p>The reasoning is that few-shot examples are largely redundant with each
 * other — that is what makes them examples — while an instruction is a list of
 * requirements where every clause matters, and the question is the one thing
 * that must survive intact.
 *
 * <p><b>Unsure means gentler, never harsher.</b> Region detection is a heuristic
 * over message roles and text markers, and the cost of the two mistakes is not
 * symmetric: calling an instruction a demonstration throws away most of it and
 * silently changes what the model was asked to do. So a message is only called
 * {@link Region#EXAMPLE} on strong evidence, and everything unrecognised falls
 * to {@link Region#HISTORY}, which is the ratio the system used before this
 * class existed.
 */
public final class CompressionPolicy {

    private CompressionPolicy() {
    }

    /** Which part of the prompt a message belongs to. */
    public enum Region {
        /** The current question. Never compressed. */
        QUESTION(1.00),
        /** System instructions — a list of requirements, where clauses matter. */
        INSTRUCTION(0.85),
        /** Earlier conversation. The pre-existing default. */
        HISTORY(0.55),
        /** Few-shot demonstrations, which are largely redundant with each other. */
        EXAMPLE(0.30);

        private final double keep;

        Region(double keep) {
            this.keep = keep;
        }

        /** Fraction of tokens to keep. */
        public double keepRatio() {
            return keep;
        }
    }

    /**
     * Compression is not free — it costs some fidelity — so it should only run
     * when it buys something. This says whether it does, and why.
     *
     * @param compress whether to compress this request at all
     * @param reason   written for the developer reading the console
     */
    public record Gate(boolean compress, String reason) {

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("compress", compress);
            m.put("reason", reason);
            return m;
        }
    }

    /** Below this many tokens in the whole prompt, compressing is not worth the fidelity cost. */
    public static final int SHORT_PROMPT_TOKENS = 400;

    /**
     * Below this estimated saving per request, compression is not worth doing.
     * A hundredth of a cent sounds trivial because it is — the point is that
     * on a cheap model even a large token saving converts to nearly no money,
     * and fidelity was spent to get it.
     */
    public static final double MIN_SAVING_USD = 0.0001;

    /**
     * Whether to compress this request.
     *
     * @param totalPromptTokens size of the whole prompt
     * @param estimatedSaving   money the expected token saving is worth, or null
     *                          when the model is not yet known — the gateway
     *                          chooses a model <em>after</em> this point, so for
     *                          {@code model: "auto"} there is genuinely nothing
     *                          to price and the size rule decides alone
     */
    public static Gate gate(int totalPromptTokens, Double estimatedSaving) {
        if (totalPromptTokens < SHORT_PROMPT_TOKENS) {
            return new Gate(false, "prompt is only " + totalPromptTokens + " tokens; below "
                    + SHORT_PROMPT_TOKENS + " there is little to remove and the fidelity cost "
                    + "outweighs the saving");
        }
        if (estimatedSaving != null && estimatedSaving < MIN_SAVING_USD) {
            return new Gate(false, String.format(
                    "the model named is cheap enough that compressing this prompt would save about "
                            + "$%.6f — not worth the loss of fidelity", estimatedSaving));
        }
        if (estimatedSaving == null) {
            return new Gate(true, totalPromptTokens + " tokens; the model is chosen later in the "
                    + "pipeline, so price could not be considered and size alone decided");
        }
        return new Gate(true, String.format("%d tokens, worth about $%.5f to compress",
                totalPromptTokens, estimatedSaving));
    }

    // --- region classification ----------------------------------------------

    /** Markers that mean "what follows is a worked example", not prose about one. */
    private static final Pattern EXAMPLE_MARKER = Pattern.compile(
            "(?m)^\\s*(?:example\\s*\\d*\\s*[:.)-]"
                    + "|(?:input|output|question|answer|q|a)\\s*[:]"
                    + "|###\\s*\\w+"
                    + "|<example>)",
            Pattern.CASE_INSENSITIVE);

    /** How many distinct marker lines before a message is called a demonstration. */
    private static final int MARKERS_REQUIRED = 2;

    /**
     * Which region a message belongs to.
     *
     * @param isLatestUserTurn whether this is the question being asked now
     */
    public static Region classify(Role role, String content, boolean isLatestUserTurn) {
        if (isLatestUserTurn) {
            return Region.QUESTION;
        }
        if (content == null || content.isBlank()) {
            return Region.HISTORY;
        }
        // Two or more marker lines. One "Output:" in a sentence of prose is not a
        // demonstration block, and treating it as one would drop 70% of a
        // message that was actually an instruction.
        var m = EXAMPLE_MARKER.matcher(content);
        int markers = 0;
        while (m.find() && markers < MARKERS_REQUIRED) {
            markers++;
        }
        if (markers >= MARKERS_REQUIRED) {
            return Region.EXAMPLE;
        }
        if (role == Role.SYSTEM) {
            return Region.INSTRUCTION;
        }
        return Region.HISTORY;
    }

    /** Human-readable label for the console. */
    public static String label(Region r) {
        return switch (r) {
            case QUESTION -> "the question";
            case INSTRUCTION -> "instructions";
            case EXAMPLE -> "examples";
            case HISTORY -> "earlier turns";
        };
    }

    /** Region names in a stable order, for a UI that must not reshuffle. */
    public static Map<String, Object> ratios() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Region r : Region.values()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("keepRatio", r.keepRatio());
            one.put("dropPct", Math.round((1 - r.keepRatio()) * 100));
            one.put("label", label(r));
            m.put(r.name().toLowerCase(Locale.ROOT), one);
        }
        return m;
    }
}
