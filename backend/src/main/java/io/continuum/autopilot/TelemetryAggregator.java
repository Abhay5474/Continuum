package io.continuum.autopilot;

import io.continuum.autopilot.stats.WelfordStats;
import io.continuum.persistence.entity.AutopilotFeedbackEntity;
import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.repository.AutopilotFeedbackRepository;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import io.continuum.persistence.repository.ReplayVerificationReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a {@link TelemetrySnapshot} for a developer from real gateway request
 * history, the ratings its users gave those answers, and its own semantic
 * replay results. No synthetic data.
 */
@Service
public class TelemetryAggregator {

    /** How many recent ratings count; older ones describe models that may have changed. */
    static final int RATINGS_CONSIDERED = 500;

    private static final Pattern REQUEST_ID = Pattern.compile("(?:req_)?(\\d{1,18})");

    private final GatewayRequestLogRepository gatewayLogs;
    private final ReplayVerificationReportRepository replayReports;
    private final AutopilotFeedbackRepository feedback;

    public TelemetryAggregator(GatewayRequestLogRepository gatewayLogs,
                               ReplayVerificationReportRepository replayReports,
                               AutopilotFeedbackRepository feedback) {
        this.gatewayLogs = gatewayLogs;
        this.replayReports = replayReports;
        this.feedback = feedback;
    }

    /**
     * One of this developer's own logged requests, by any id a caller holds for
     * it: the public {@code req_42}, the bare log id, or the trace id. Someone
     * else's request reads as no request.
     */
    @Transactional(readOnly = true)
    public Optional<GatewayRequestLogEntity> ownRequest(String developerId, String ref) {
        if (developerId == null || ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        String r = ref.strip();
        Matcher m = REQUEST_ID.matcher(r);
        Optional<GatewayRequestLogEntity> found = m.matches()
                ? gatewayLogs.findById(Long.parseLong(m.group(1)))
                : gatewayLogs.findFirstByTraceIdAndDeveloperId(r, developerId);
        return found.filter(g -> developerId.equals(g.getDeveloperId()));
    }

    @Transactional(readOnly = true)
    public TelemetrySnapshot snapshot(String developerId) {
        Map<String, TelemetrySnapshot.Arm> arms = new LinkedHashMap<>();
        long totalSuccess = 0, totalFail = 0;
        WelfordStats latency = new WelfordStats();
        double costSumWeighted = 0;

        for (var o : gatewayLogs.providerOutcomesForDeveloper(developerId)) {
            arms.put(o.getProvider(), new TelemetrySnapshot.Arm(o.getSuccesses(), o.getFailures(),
                    o.getAvgLatency(), o.getAvgCost()));
        }
        // An answer that arrived but that the developer's users rated bad did
        // not succeed in any sense that matters. Ratings were stored and never
        // read, so the thumbs-down a customer gave changed nothing.
        for (Map.Entry<String, Long> bad : ratedBad(developerId).entrySet()) {
            TelemetrySnapshot.Arm a = arms.get(bad.getKey());
            if (a == null) {
                continue;
            }
            long moved = Math.min(bad.getValue(), a.successes());
            arms.put(bad.getKey(), new TelemetrySnapshot.Arm(a.successes() - moved, a.failures() + moved,
                    a.avgLatencyMs(), a.avgCostUsd()));
        }
        for (TelemetrySnapshot.Arm a : arms.values()) {
            long s = a.successes(), f = a.failures();
            totalSuccess += s;
            totalFail += f;
            // Approximate the latency distribution from per-provider means, weighted by volume.
            for (long i = 0; i < (s + f); i++) {
                latency.add(a.avgLatencyMs());
            }
            costSumWeighted += a.avgCostUsd() * (s + f);
        }

        long total = totalSuccess + totalFail;
        double successRate = total == 0 ? 1.0 : (double) totalSuccess / total;
        double avgCost = total == 0 ? 0.0 : costSumWeighted / total;
        double p95 = latency.approxPercentile(1.645);
        long failuresPrevented = gatewayLogs.totalFailoversForDeveloper(developerId);

        double replayPass = replayPassRate(developerId);

        return new TelemetrySnapshot(total, successRate, latency.mean(), p95, avgCost,
                failuresPrevented, replayPass, arms);
    }

    /** Successful answers the developer's users rated bad, per provider; the latest rating of an answer counts. */
    private Map<String, Long> ratedBad(String developerId) {
        Map<String, Long> out = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (AutopilotFeedbackEntity f : feedback.findByDeveloperIdOrderByCreatedAtDesc(developerId)
                .stream().limit(RATINGS_CONSIDERED).toList()) {
            if (!seen.add(f.getRequestRef()) || f.getScore() >= 0) {
                continue;
            }
            ownRequest(developerId, f.getRequestRef())
                    .filter(g -> g.isSuccess() && g.getChosenProvider() != null)
                    .ifPresent(g -> out.merge(g.getChosenProvider(), 1L, Long::sum));
        }
        return out;
    }

    /** This developer's replay pass rate; it used to count every account's reports. */
    private double replayPassRate(String developerId) {
        long passed = replayReports.countByPassedAndDeveloperId(true, developerId);
        long failed = replayReports.countByPassedAndDeveloperId(false, developerId);
        long tot = passed + failed;
        return tot == 0 ? 1.0 : (double) passed / tot;
    }
}
