package io.continuum.autopilot.engine;

import io.continuum.autopilot.TelemetrySnapshot;
import io.continuum.autopilot.model.AutopilotMode;
import io.continuum.autopilot.model.DeveloperProfile;
import io.continuum.autopilot.model.PolicyBundle;
import io.continuum.autopilot.stats.BetaDistribution;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Proposes a new {@link PolicyBundle} from telemetry using genuine, modular
 * optimization — not an if/else rule engine:
 *
 *  - <b>Contextual bandit (Thompson sampling)</b>: each provider is an arm with a
 *    Beta posterior over its success rate; we sample utilities that blend
 *    sampled quality with normalized cost/latency under the developer's mode
 *    weights, and rank providers by expected utility.
 *  - <b>Constrained optimization</b>: providers breaching the per-request cost or
 *    latency budget are excluded (never all — a fallback is always kept).
 *  - <b>Latency-aware hedge tuning</b>: the hedge threshold moves toward the p95
 *    latency vs the budget.
 *
 * Deterministic enough to unit-test: utilities are averaged over many samples, so
 * clearly-separated arms rank stably regardless of RNG seed.
 */
@Component
public class DecisionEngine {

    private static final int SAMPLES = 400;

    private final Random rng;

    public DecisionEngine() {
        this.rng = new Random();
    }

    /** Test constructor with a seeded RNG for reproducibility. */
    DecisionEngine(Random rng) {
        this.rng = rng;
    }

    public record Proposal(PolicyBundle candidate, String rationale, double confidence, boolean changed) {
    }

    public Proposal propose(PolicyBundle current, TelemetrySnapshot snapshot, DeveloperProfile profile) {
        AutopilotMode mode = profile.mode();
        List<String> allowed = profile.allowedProviders() == null || profile.allowedProviders().isEmpty()
                ? current.providerOrder() : profile.allowedProviders();

        // Gather candidate arms (allowed providers), using a uniform prior when no data.
        List<Cand> cands = new ArrayList<>();
        for (String p : allowed) {
            TelemetrySnapshot.Arm arm = snapshot.providerArms().getOrDefault(p,
                    new TelemetrySnapshot.Arm(0, 0, 0, 0));
            cands.add(new Cand(p, arm));
        }
        if (cands.isEmpty()) {
            return new Proposal(current, "No allowed providers with telemetry; keeping current policy.", 0.0, false);
        }

        // Constrained optimization: exclude budget/latency violators (keep >=1).
        List<Cand> feasible = new ArrayList<>(cands.stream()
                .filter(c -> withinBudget(c, current))
                .toList());
        if (feasible.isEmpty()) {
            feasible = cands; // never strand the developer
        }

        double minCost = feasible.stream().mapToDouble(c -> c.arm.avgCostUsd()).min().orElse(0);
        double maxCost = feasible.stream().mapToDouble(c -> c.arm.avgCostUsd()).max().orElse(0);
        double minLat = feasible.stream().mapToDouble(c -> c.arm.avgLatencyMs()).min().orElse(0);
        double maxLat = feasible.stream().mapToDouble(c -> c.arm.avgLatencyMs()).max().orElse(0);

        // Thompson-sampled expected utility per arm.
        for (Cand c : feasible) {
            BetaDistribution posterior = BetaDistribution.fromCounts(c.arm.successes(), c.arm.failures());
            double costScore = 1.0 - norm(c.arm.avgCostUsd(), minCost, maxCost);
            double latScore = 1.0 - norm(c.arm.avgLatencyMs(), minLat, maxLat);
            double acc = 0;
            for (int i = 0; i < SAMPLES; i++) {
                double q = posterior.sample(rng);
                acc += mode.wQuality * q + mode.wCost * costScore + mode.wLatency * latScore;
            }
            c.score = acc / SAMPLES;
        }
        feasible.sort(Comparator.comparingDouble((Cand c) -> c.score).reversed());

        List<String> newOrder = feasible.stream().map(c -> c.provider).toList();

        // Latency-aware hedge tuning toward the p95 vs the latency budget.
        long newHedge = tuneHedge(current.hedgeThresholdMs(), snapshot.p95LatencyMs(), profile.maxLatencyMs());

        String routingMode = switch (mode) {
            case LOW_COST -> "LOW_COST";
            case LOW_LATENCY -> "LOW_LATENCY";
            case HIGH_QUALITY, SAFETY_FIRST -> "HIGH_QUALITY";
            case BALANCED -> "BALANCED";
        };

        PolicyBundle candidate = current
                .withProviderOrder(newOrder)
                .withRoutingMode(routingMode)
                .withHedgeThresholdMs(newHedge);

        boolean changed = !newOrder.equals(current.providerOrder())
                || newHedge != current.hedgeThresholdMs()
                || !routingMode.equals(current.routingMode());

        double confidence = confidence(feasible, snapshot.totalRequests());
        String rationale = buildRationale(mode, feasible, current, newOrder, newHedge, snapshot);

        return new Proposal(candidate, rationale, confidence, changed);
    }

    private boolean withinBudget(Cand c, PolicyBundle current) {
        if (c.arm.total() == 0) {
            return true; // no data yet — give it a chance under canary later
        }
        boolean costOk = c.arm.avgCostUsd() <= current.costCapUsd() * 1.05 || c.arm.avgCostUsd() == 0;
        boolean latOk = c.arm.avgLatencyMs() <= current.latencyCapMs() * 1.5 || c.arm.avgLatencyMs() == 0;
        return costOk && latOk;
    }

    private long tuneHedge(long currentHedge, double p95, long latencyBudget) {
        if (latencyBudget <= 0) {
            return currentHedge;
        }
        if (p95 > latencyBudget) {
            return Math.max(200, Math.round(latencyBudget * 0.6)); // hedge earlier
        }
        if (p95 > 0 && p95 < latencyBudget * 0.5) {
            return Math.min(2000, Math.round(currentHedge * 1.5)); // hedge later, save cost
        }
        return currentHedge;
    }

    private double confidence(List<Cand> ranked, long totalRequests) {
        if (ranked.size() < 2) {
            return Math.min(1.0, totalRequests / 50.0);
        }
        double sep = ranked.get(0).score - ranked.get(1).score;
        double dataFactor = Math.min(1.0, totalRequests / 50.0);
        return Math.max(0.0, Math.min(1.0, sep * 4.0)) * dataFactor;
    }

    private String buildRationale(AutopilotMode mode, List<Cand> ranked, PolicyBundle current,
                                  List<String> newOrder, long newHedge, TelemetrySnapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append("Mode ").append(mode).append(". ");
        if (!newOrder.equals(current.providerOrder())) {
            sb.append("Reorder providers to ").append(newOrder)
                    .append(" (top arm ").append(ranked.get(0).provider)
                    .append(" score ").append(round(ranked.get(0).score)).append("). ");
        } else {
            sb.append("Provider order unchanged. ");
        }
        if (newHedge != current.hedgeThresholdMs()) {
            sb.append("Adjust hedge threshold ").append(current.hedgeThresholdMs())
                    .append("ms → ").append(newHedge).append("ms (p95 ")
                    .append(round(s.p95LatencyMs())).append("ms). ");
        }
        return sb.toString().trim();
    }

    private static double norm(double v, double min, double max) {
        return max <= min ? 0.0 : (v - min) / (max - min);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static final class Cand {
        final String provider;
        final TelemetrySnapshot.Arm arm;
        double score;

        Cand(String provider, TelemetrySnapshot.Arm arm) {
            this.provider = provider;
            this.arm = arm;
        }
    }
}
