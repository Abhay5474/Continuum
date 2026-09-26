package io.continuum.routing;

import io.continuum.persistence.entity.ProviderStatsEntity;
import io.continuum.persistence.repository.ReplayVerificationReportRepository;
import org.springframework.stereotype.Component;

/**
 * Predicts a provider's quality in [0,1] from measured signals only:
 *
 *  - reliability: 1 - error rate (a provider that errors a lot is low quality in practice),
 *  - fidelity: the mean semantic-replay overall score for outputs it produced
 *    (when Extension 1 verification data exists),
 *
 * blended with a confidence factor that grows with sample size. With no data the
 * predictor returns a neutral 0.5 and flags low confidence — it never fabricates
 * a ranking.
 */
@Component
public class QualityPredictor {

    private final ReplayVerificationReportRepository reports;

    public QualityPredictor(ReplayVerificationReportRepository reports) {
        this.reports = reports;
    }

    public Quality predict(String provider, ProviderStatsEntity stats) {
        long calls = stats == null ? 0 : stats.getCalls();
        if (calls == 0) {
            return new Quality(0.5, 0.0, "no runtime data — neutral prior");
        }
        double reliability = 1.0 - stats.getErrorRate();

        // Fidelity from semantic verification, if any reports reference this provider.
        Double fidelity = meanFidelity(provider);
        double quality = fidelity == null ? reliability : 0.6 * reliability + 0.4 * fidelity;

        // Confidence saturates around 30 calls.
        double confidence = Math.min(1.0, calls / 30.0);
        String basis = "reliability=" + round(reliability)
                + (fidelity != null ? ", fidelity=" + round(fidelity) : ", fidelity=n/a")
                + ", n=" + calls;
        return new Quality(quality, confidence, basis);
    }

    /**
     * Averaged by the database. This runs for every provider on every routed
     * request, and it used to load the whole replay-report table into memory
     * each time to average one provider's rows — a cost that grew with history.
     */
    private Double meanFidelity(String provider) {
        return provider == null ? null : reports.meanScoreFor(provider);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    public record Quality(double score, double confidence, String basis) {
    }
}
