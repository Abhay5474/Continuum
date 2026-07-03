package io.continuum.autopilot;

import io.continuum.autopilot.stats.WelfordStats;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import io.continuum.persistence.repository.ReplayVerificationReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a {@link TelemetrySnapshot} for a developer from real gateway request
 * history (and a global semantic-replay quality signal). No synthetic data.
 */
@Service
public class TelemetryAggregator {

    private final GatewayRequestLogRepository gatewayLogs;
    private final ReplayVerificationReportRepository replayReports;

    public TelemetryAggregator(GatewayRequestLogRepository gatewayLogs,
                               ReplayVerificationReportRepository replayReports) {
        this.gatewayLogs = gatewayLogs;
        this.replayReports = replayReports;
    }

    @Transactional(readOnly = true)
    public TelemetrySnapshot snapshot(String developerId) {
        Map<String, TelemetrySnapshot.Arm> arms = new LinkedHashMap<>();
        long totalSuccess = 0, totalFail = 0;
        WelfordStats latency = new WelfordStats();
        double costSumWeighted = 0;

        for (var o : gatewayLogs.providerOutcomesForDeveloper(developerId)) {
            long s = o.getSuccesses(), f = o.getFailures();
            arms.put(o.getProvider(), new TelemetrySnapshot.Arm(s, f, o.getAvgLatency(), o.getAvgCost()));
            totalSuccess += s;
            totalFail += f;
            // Approximate the latency distribution from per-provider means, weighted by volume.
            for (long i = 0; i < (s + f); i++) {
                latency.add(o.getAvgLatency());
            }
            costSumWeighted += o.getAvgCost() * (s + f);
        }

        long total = totalSuccess + totalFail;
        double successRate = total == 0 ? 1.0 : (double) totalSuccess / total;
        double avgCost = total == 0 ? 0.0 : costSumWeighted / total;
        double p95 = latency.approxPercentile(1.645);
        long failuresPrevented = gatewayLogs.totalFailoversForDeveloper(developerId);

        double replayPass = replayPassRate();

        return new TelemetrySnapshot(total, successRate, latency.mean(), p95, avgCost,
                failuresPrevented, replayPass, arms);
    }

    private double replayPassRate() {
        long passed = replayReports.countByPassed(true);
        long failed = replayReports.countByPassed(false);
        long tot = passed + failed;
        return tot == 0 ? 1.0 : (double) passed / tot;
    }
}
