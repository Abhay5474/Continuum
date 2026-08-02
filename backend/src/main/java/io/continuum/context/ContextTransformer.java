package io.continuum.context;

/**
 * One deterministic transformation from application data into canonical context.
 *
 * <p>Transformers are chosen by looking at the bytes, not by asking the caller.
 * A developer posting a file should not have to declare what it is — they
 * frequently do not know, their upload widget frequently lies, and being wrong
 * about it produces a worse failure than being unsure.
 *
 * <p>Implementations must be <b>pure</b>: no network, no API key, no model, no
 * clock, no randomness. The same bytes must produce the same canonical context
 * on any machine at any time, because this output goes into prompts that are
 * cached, replayed and audited.
 */
public interface ContextTransformer {

    /** Stable identifier, used in the API and the console. */
    String name();

    /** What a person would call this. */
    String label();

    ContextType produces();

    /**
     * Whether this transformer recognises the input.
     *
     * <p>Cheap and byte-based. Must not throw and must not consume the array.
     *
     * @param filename may be null — a hint at best, never the deciding factor
     */
    boolean supports(byte[] input, String filename);

    /**
     * Performs the transformation.
     *
     * <p>Must not throw. Input that turns out to be malformed after
     * {@link #supports} said yes is a normal occurrence — a truncated upload, a
     * password-protected workbook — and the honest response is a canonical
     * context carrying the problem as an {@link Ambiguity}, not an exception
     * that loses everything parsed so far.
     */
    CanonicalContext transform(byte[] input, String filename);

    /**
     * A plain-text rendering of the raw input, for the before/after comparison.
     *
     * <p>Each transformer knows what "the naive version" of its input looks
     * like: a CSV dump for a workbook, the log lines themselves, the raw
     * message bodies. Measuring against that is the only honest baseline —
     * comparing canonical output against the base64 of a binary file would
     * report an enormous saving that means nothing.
     */
    String rawTextBaseline(byte[] input, String filename);
}
