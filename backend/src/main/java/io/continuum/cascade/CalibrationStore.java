package io.continuum.cascade;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns the judge's raw score into a probability, using this tenant's own
 * outcomes.
 *
 * <p>A raw score is not a probability, and treating one as the other is the
 * mistake UCCI (2026) documents for token-level confidence: the number is
 * monotone-ish with correctness but badly scaled, so a fixed threshold means
 * something different on every workload. Calibration fixes the scale without
 * touching the ordering.
 *
 * <p>The method is isotonic-flavoured rather than full isotonic regression:
 * scores are binned, each bin holds the observed rate at which the cheap answer
 * turned out to be sufficient, and the bins are then made monotone by pooling
 * adjacent violators. That is the pool-adjacent-violators step of isotonic
 * regression, which is the part that matters here — the guarantee that a higher
 * score never maps to a lower probability, so raising the threshold can only
 * ever escalate more.
 *
 * <p>Bins with too little evidence fall back to the raw score. Claiming a
 * calibrated probability from four observations would be worse than not
 * calibrating at all.
 */
@Component
public class CalibrationStore {

    private static final int BINS = 10;
    /** Below this an individual bin is noise, not evidence. */
    private static final int MIN_SAMPLES = 8;

    /** developerId → bins. */
    private final Map<String, Bin[]> byDeveloper = new ConcurrentHashMap<>();

    private static final class Bin {
        long total;
        long sufficient;

        double rate() {
            return total == 0 ? Double.NaN : (double) sufficient / total;
        }
    }

    /**
     * Records an outcome.
     *
     * @param sufficient whether the cheap answer turned out to be good enough,
     *                   determined by comparing it against the strong model's
     *                   answer — not by asking anyone
     */
    public void observe(String developerId, double rawScore, boolean sufficient) {
        if (developerId == null) {
            return;
        }
        Bin[] bins = byDeveloper.computeIfAbsent(developerId, k -> newBins());
        Bin b = bins[index(rawScore)];
        synchronized (b) {
            b.total++;
            if (sufficient) {
                b.sufficient++;
            }
        }
    }

    /** Maps a raw score to a calibrated probability that the cheap answer suffices. */
    public double calibrate(String developerId, double rawScore) {
        Bin[] bins = byDeveloper.get(developerId);
        if (bins == null) {
            return rawScore;
        }
        double[] curve = monotoneCurve(bins);
        double v = curve[index(rawScore)];
        return Double.isNaN(v) ? rawScore : v;
    }

    /** How much evidence backs this tenant's curve, for the console. */
    public Map<String, Object> profile(String developerId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Bin[] bins = byDeveloper.get(developerId);
        if (bins == null) {
            out.put("observations", 0L);
            out.put("calibrated", false);
            out.put("curve", List.of());
            return out;
        }
        double[] curve = monotoneCurve(bins);
        long total = 0;
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < BINS; i++) {
            total += bins[i].total;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("bin", i);
            p.put("rawFrom", (double) i / BINS);
            p.put("rawTo", (double) (i + 1) / BINS);
            p.put("samples", bins[i].total);
            p.put("calibrated", Double.isNaN(curve[i]) ? null : curve[i]);
            points.add(p);
        }
        out.put("observations", total);
        out.put("calibrated", total >= MIN_SAMPLES);
        out.put("curve", points);
        return out;
    }

    public void reset(String developerId) {
        byDeveloper.remove(developerId);
    }

    /**
     * Bin rates, made non-decreasing by pooling adjacent violators.
     *
     * <p>Without this a sparse bin can invert the curve, and a higher judge
     * score would map to a lower probability — which would make the threshold
     * behave non-monotonically and the whole control unusable.
     */
    private static double[] monotoneCurve(Bin[] bins) {
        double[] rate = new double[BINS];
        long[] weight = new long[BINS];
        for (int i = 0; i < BINS; i++) {
            synchronized (bins[i]) {
                weight[i] = bins[i].total;
                rate[i] = bins[i].total >= MIN_SAMPLES ? bins[i].rate() : Double.NaN;
            }
        }
        // Pool adjacent violators over the bins that have evidence.
        boolean changed = true;
        while (changed) {
            changed = false;
            int prev = -1;
            for (int i = 0; i < BINS; i++) {
                if (Double.isNaN(rate[i])) {
                    continue;
                }
                if (prev >= 0 && rate[i] < rate[prev]) {
                    long w = weight[prev] + weight[i];
                    double pooled = w == 0 ? rate[i]
                            : (rate[prev] * weight[prev] + rate[i] * weight[i]) / w;
                    rate[prev] = pooled;
                    rate[i] = pooled;
                    weight[prev] = w;
                    weight[i] = w;
                    changed = true;
                }
                prev = i;
            }
        }
        return rate;
    }

    private static Bin[] newBins() {
        Bin[] b = new Bin[BINS];
        for (int i = 0; i < BINS; i++) {
            b[i] = new Bin();
        }
        return b;
    }

    private static int index(double rawScore) {
        int i = (int) (Math.max(0, Math.min(0.999999, rawScore)) * BINS);
        return Math.max(0, Math.min(BINS - 1, i));
    }
}
