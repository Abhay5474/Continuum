package io.continuum.godmode.memory;

/**
 * Deterministic, dependency-free text embedding via the hashing trick:
 * lower-cased terms are hashed into a fixed-size bag-of-words vector, then
 * L2-normalized. Not a neural embedding — but deterministic, fast, and good
 * enough for near-duplicate detection and similarity edges in the experience
 * graph without shipping tokens to a provider. Swappable for a real embedding
 * model later without schema changes (vectors are stored as JSON).
 */
public final class TextEmbedder {

    public static final int DIMENSIONS = 128;

    public double[] embed(String text) {
        double[] v = new double[DIMENSIONS];
        if (text == null || text.isBlank()) {
            return v;
        }
        for (String term : text.toLowerCase().split("[^a-z0-9]+")) {
            if (term.length() < 3) {
                continue;
            }
            int h = Math.floorMod(term.hashCode(), DIMENSIONS);
            v[h] += 1.0;
        }
        double norm = 0;
        for (double x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) {
                v[i] /= norm;
            }
        }
        return v;
    }

    public static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot; // vectors are already L2-normalized
    }
}
