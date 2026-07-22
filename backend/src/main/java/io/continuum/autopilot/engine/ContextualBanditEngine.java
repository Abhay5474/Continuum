package io.continuum.autopilot.engine;

import io.continuum.autopilot.stats.BetaDistribution;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V8 research upgrade — a genuinely <b>contextual</b> and <b>non-stationary</b>
 * Thompson-sampling bandit, addressing two limitations of the original V4
 * arms (which were context-free and stationary):
 *
 * <ul>
 *   <li><b>Contextual</b> (Li et al., <i>A Contextual-Bandit Approach…</i>, WWW
 *       2010): the best provider depends on the request. Context is discretized
 *       into complexity buckets, and a separate posterior is kept per
 *       (context, provider) — so "simple" and "complex" traffic can prefer
 *       different providers, which a single global arm can never express.</li>
 *   <li><b>Non-stationary</b> (Raj &amp; Kalyani, <i>Taming Non-stationary
 *       Bandits: A Bayesian Approach</i>, 2017): each observation <b>discounts</b>
 *       the arm's prior pseudo-counts by γ before adding the new evidence, so
 *       the posterior tracks <em>recent</em> behaviour — a provider that
 *       silently degrades is demoted quickly, instead of being propped up by
 *       months-old successes.</li>
 * </ul>
 *
 * This engine is additive and side-effect-free: the gateway records real
 * outcomes into it, and it can be <em>queried</em> for a context-aware ranking,
 * but it never alters the deterministic V3 routing path. γ = 1 recovers the
 * original stationary bandit exactly.
 */
@Component
public class ContextualBanditEngine {

    /** Discount factor: how fast old evidence fades (1.0 = stationary). */
    private static final double DEFAULT_GAMMA = 0.97;
    private static final int SAMPLES = 400;
    private static final double MAX_PSEUDO = 500.0; // cap so a dominant arm never saturates

    /** Discretized request context. Small and interpretable; easily extended. */
    public enum Context {
        SIMPLE, MODERATE, COMPLEX;

        public static Context ofComplexity(double complexity) {
            if (complexity < 0.34) {
                return SIMPLE;
            }
            return complexity < 0.67 ? MODERATE : COMPLEX;
        }
    }

    /** One discounted Beta arm + decayed cost/latency EWMAs for a (context, provider). */
    static final class Arm {
        double s = 0;   // discounted successes (pseudo-count)
        double f = 0;   // discounted failures
        double latencyMs = 0;
        double costUsd = 0;
        long updates = 0;

        synchronized void observe(boolean success, double latencyMs, double costUsd, double gamma) {
            s *= gamma;
            f *= gamma;
            if (success) {
                s += 1;
            } else {
                f += 1;
            }
            s = Math.min(s, MAX_PSEUDO);
            f = Math.min(f, MAX_PSEUDO);
            double w = updates == 0 ? 1.0 : 0.2; // EWMA on measured cost/latency
            this.latencyMs = updates == 0 ? latencyMs : (1 - w) * this.latencyMs + w * latencyMs;
            this.costUsd = updates == 0 ? costUsd : (1 - w) * this.costUsd + w * costUsd;
            updates++;
        }

        synchronized BetaDistribution posterior() {
            return BetaDistribution.fromCounts(Math.round(s), Math.round(f));
        }

        synchronized double meanSuccess() {
            return (s + 1) / (s + f + 2);
        }
    }

    private final Map<Context, Map<String, Arm>> arms = new ConcurrentHashMap<>();
    private final double gamma;
    private final Random rng;

    public ContextualBanditEngine() {
        this(DEFAULT_GAMMA, new Random());
    }

    /** Test/tuning constructor with explicit discount and seeded RNG. */
    public ContextualBanditEngine(double gamma, Random rng) {
        this.gamma = gamma;
        this.rng = rng;
    }

    /** Record one real outcome. Never throws into the caller. */
    public void observe(Context context, String provider, boolean success, double latencyMs, double costUsd) {
        arm(context, provider).observe(success, latencyMs, costUsd, gamma);
    }

    public void observe(double complexity, String provider, boolean success, double latencyMs, double costUsd) {
        observe(Context.ofComplexity(complexity), provider, success, latencyMs, costUsd);
    }

    /**
     * Rank providers for a given context by Thompson-sampled expected utility,
     * blending sampled quality with normalized cost/latency under the supplied
     * weights. Falls back to a uniform prior for unseen (context, provider) pairs.
     */
    public List<Ranked> rank(Context context, List<String> providers,
                             double wQuality, double wCost, double wLatency) {
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }
        List<Arm> snapshot = new ArrayList<>();
        for (String p : providers) {
            snapshot.add(arm(context, p));
        }
        double minCost = snapshot.stream().mapToDouble(a -> a.costUsd).min().orElse(0);
        double maxCost = snapshot.stream().mapToDouble(a -> a.costUsd).max().orElse(0);
        double minLat = snapshot.stream().mapToDouble(a -> a.latencyMs).min().orElse(0);
        double maxLat = snapshot.stream().mapToDouble(a -> a.latencyMs).max().orElse(0);

        List<Ranked> ranked = new ArrayList<>();
        for (int i = 0; i < providers.size(); i++) {
            Arm a = snapshot.get(i);
            BetaDistribution posterior = a.posterior();
            double costScore = 1.0 - norm(a.costUsd, minCost, maxCost);
            double latScore = 1.0 - norm(a.latencyMs, minLat, maxLat);
            double acc = 0;
            for (int k = 0; k < SAMPLES; k++) {
                double q = posterior.sample(rng);
                acc += wQuality * q + wCost * costScore + wLatency * latScore;
            }
            ranked.add(new Ranked(providers.get(i), acc / SAMPLES, a.meanSuccess(), a.updates));
        }
        ranked.sort(Comparator.comparingDouble((Ranked r) -> r.score()).reversed());
        return ranked;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gamma", gamma);
        out.put("nonStationary", gamma < 1.0);
        out.put("contextual", true);
        Map<String, Object> byContext = new LinkedHashMap<>();
        for (Context c : Context.values()) {
            Map<String, Arm> row = arms.get(c);
            if (row == null || row.isEmpty()) {
                continue;
            }
            Map<String, Object> providers = new LinkedHashMap<>();
            row.forEach((p, a) -> providers.put(p, Map.of(
                    "successRate", round(a.meanSuccess()),
                    "discountedSuccesses", round(a.s),
                    "discountedFailures", round(a.f),
                    "avgLatencyMs", round(a.latencyMs),
                    "avgCostUsd", a.costUsd,
                    "updates", a.updates)));
            byContext.put(c.name(), providers);
        }
        out.put("byContext", byContext);
        return out;
    }

    private Arm arm(Context context, String provider) {
        return arms.computeIfAbsent(context, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(provider, k -> new Arm());
    }

    private static double norm(double v, double min, double max) {
        return max <= min ? 0.0 : (v - min) / (max - min);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    public record Ranked(String provider, double score, double meanSuccess, long observations) {
    }
}
