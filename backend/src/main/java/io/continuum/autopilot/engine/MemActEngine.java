package io.continuum.autopilot.engine;

import io.continuum.autopilot.stats.BetaDistribution;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * Memory-as-Action policy engine — the V4 decision-engine family extended for
 * God Mode. It does NOT reinvent the bandit: each memory action is an arm with
 * the same {@link BetaDistribution} Beta(successes+1, failures+1) posterior the
 * V4 {@link DecisionEngine} uses for providers, selected by Thompson sampling.
 *
 * An action "succeeds" when it produced real value (a summarization that
 * compressed well, a prune that removed a true near-duplicate, a promotion
 * whose node was later retrieved); rewards are fed back via {@link #observe}.
 * SUMMARIZE_NOW / DEFER competition also gets a context-pressure prior: the
 * fuller the working tier, the more valuable summarization is a priori.
 */
@Component
public class MemActEngine {

    public enum MemoryAction { SUMMARIZE_NOW, DEFER, PRUNE_DUPLICATES, PROMOTE_EXPERIENCE, ARCHIVE_COLD }

    private final Map<MemoryAction, long[]> counts = new EnumMap<>(MemoryAction.class); // [successes, failures]
    private final Random rng;

    public MemActEngine() {
        this(new Random());
    }

    MemActEngine(Random rng) {
        this.rng = rng;
        for (MemoryAction a : MemoryAction.values()) {
            counts.put(a, new long[]{0, 0});
        }
    }

    /**
     * Should the working tier be summarized now, or deferred?
     * Thompson-samples both arms; the summarize arm's sampled utility is scaled
     * by context pressure (fill fraction of the token budget) so a nearly-full
     * context strongly favors consolidation regardless of sparse history.
     */
    public synchronized boolean shouldSummarize(double contextFillFraction) {
        double pressure = Math.max(0.0, Math.min(1.0, contextFillFraction));
        double summarize = posterior(MemoryAction.SUMMARIZE_NOW).sample(rng) * (0.5 + pressure);
        double defer = posterior(MemoryAction.DEFER).sample(rng) * (1.0 - 0.6 * pressure);
        return summarize >= defer;
    }

    /** Thompson-sample whether a maintenance action is worth running this tick. */
    public synchronized boolean shouldRun(MemoryAction action) {
        double act = posterior(action).sample(rng);
        return act >= 0.35; // arms decay toward skipping only on repeated failure
    }

    /** Feed back the realized outcome of an action (reward in [0,1]). */
    public synchronized void observe(MemoryAction action, double reward) {
        long[] c = counts.get(action);
        if (reward >= 0.5) {
            c[0]++;
        } else {
            c[1]++;
        }
    }

    public synchronized Map<String, Object> stateSnapshot() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (MemoryAction a : MemoryAction.values()) {
            long[] c = counts.get(a);
            BetaDistribution d = BetaDistribution.fromCounts(c[0], c[1]);
            out.put(a.name(), Map.of("successes", c[0], "failures", c[1],
                    "posteriorMean", d.mean(), "lower95", d.lowerBound95()));
        }
        return out;
    }

    private BetaDistribution posterior(MemoryAction action) {
        long[] c = counts.get(action);
        return BetaDistribution.fromCounts(c[0], c[1]);
    }
}
