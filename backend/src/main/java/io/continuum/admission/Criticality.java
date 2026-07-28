package io.continuum.admission;

import java.util.Locale;

/**
 * How much a request deserves the last free slot.
 *
 * <p>From Google SRE's <i>Handling Overload</i>: when a system is past capacity
 * it will refuse something, and the only question is whether it refuses
 * deliberately or at random. Refusing at random is the worst outcome available —
 * it means a batch summarisation job and a customer waiting on a live chat have
 * exactly the same chance of being dropped.
 *
 * <p>Each level carries the utilisation at which it starts being refused.
 * {@code CRITICAL} is allowed to exceed the inferred limit, because the limit is
 * an estimate and the cost of being wrong about an interactive request is higher
 * than the cost of one queued call at the provider.
 */
public enum Criticality {

    /** Bulk work nobody is waiting for. First to go. */
    BACKGROUND(0.70),
    /** The default. */
    NORMAL(1.00),
    /** Someone is watching a cursor blink. May overshoot the estimate. */
    CRITICAL(1.30);

    private final double sheddingPoint;

    Criticality(double sheddingPoint) {
        this.sheddingPoint = sheddingPoint;
    }

    /** Utilisation, as a fraction of the inferred limit, at which this sheds. */
    public double sheddingPoint() {
        return sheddingPoint;
    }

    /**
     * Forgiving, and defaults to {@code NORMAL}.
     *
     * <p>An unrecognised value must never be read as {@code BACKGROUND}: a typo
     * in a client would silently make that caller's traffic the first thing
     * dropped under load, which is a very quiet way to break someone.
     */
    public static Criticality of(String raw) {
        if (raw == null || raw.isBlank()) {
            return NORMAL;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NORMAL;
        }
    }
}
