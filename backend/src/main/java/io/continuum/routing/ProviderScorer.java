package io.continuum.routing;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns measured candidate metrics into comparable scores under a routing
 * policy. Cost and latency are min-max normalized across the candidate set (so
 * scoring is relative and adapts as runtime stats change); quality is already in
 * [0,1]. Task complexity shifts weight toward quality for harder tasks.
 *
 * Pure and deterministic for a given candidate set — unit tested.
 */
@Component
public class ProviderScorer {

    public record Candidate(String provider, double predictedCostUsd, double avgLatencyMs,
                            double quality, double qualityConfidence, boolean hasData) {
    }

    public List<ProviderScore> score(List<Candidate> candidates, RoutingPolicy policy, double complexity) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        double minCost = candidates.stream().mapToDouble(Candidate::predictedCostUsd).min().orElse(0);
        double maxCost = candidates.stream().mapToDouble(Candidate::predictedCostUsd).max().orElse(0);
        // For latency, only providers with data participate in the range.
        double minLat = candidates.stream().filter(Candidate::hasData)
                .mapToDouble(Candidate::avgLatencyMs).min().orElse(0);
        double maxLat = candidates.stream().filter(Candidate::hasData)
                .mapToDouble(Candidate::avgLatencyMs).max().orElse(0);

        // Complexity tilts weighting toward quality (harder task => quality matters more).
        double wQuality = policy.wQuality() + 0.3 * complexity;
        double wCost = policy.wCost();
        double wLatency = policy.wLatency();
        double wSum = wQuality + wCost + wLatency;
        wQuality /= wSum;
        wCost /= wSum;
        wLatency /= wSum;

        List<ProviderScore> out = new ArrayList<>();
        for (Candidate c : candidates) {
            double costScore = 1.0 - normalize(c.predictedCostUsd(), minCost, maxCost);
            double latencyScore = c.hasData() ? 1.0 - normalize(c.avgLatencyMs(), minLat, maxLat) : 0.5;
            double qualityScore = c.quality();

            boolean disqualified = false;
            StringBuilder notes = new StringBuilder();
            if (policy.maxBudgetUsd() != null && c.predictedCostUsd() > policy.maxBudgetUsd()) {
                disqualified = true;
                notes.append("over budget; ");
            }
            if (policy.requiredLatencyMs() != null && c.hasData() && c.avgLatencyMs() > policy.requiredLatencyMs()) {
                disqualified = true;
                notes.append("over latency SLO; ");
            }
            if (policy.requiredQuality() != null && c.quality() < policy.requiredQuality()) {
                disqualified = true;
                notes.append("below quality floor; ");
            }
            if (!c.hasData()) {
                notes.append("cold start (neutral latency/quality); ");
            }

            double total = wCost * costScore + wLatency * latencyScore + wQuality * qualityScore;
            if (disqualified) {
                total = -1;
            }
            out.add(new ProviderScore(c.provider(), total, costScore, latencyScore, qualityScore,
                    c.predictedCostUsd(), c.avgLatencyMs(), c.quality(), c.qualityConfidence(),
                    disqualified, notes.toString().trim()));
        }
        out.sort(Comparator.comparingDouble(ProviderScore::totalScore).reversed());
        return out;
    }

    private static double normalize(double v, double min, double max) {
        if (max <= min) {
            return 0.0; // all equal -> neutral best
        }
        return (v - min) / (max - min);
    }
}
