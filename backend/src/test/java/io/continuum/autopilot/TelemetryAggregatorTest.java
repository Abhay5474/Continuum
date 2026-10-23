package io.continuum.autopilot;

import io.continuum.persistence.entity.AutopilotFeedbackEntity;
import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.repository.AutopilotFeedbackRepository;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import io.continuum.persistence.repository.ReplayVerificationReportRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ratings from a developer's users count in the telemetry Autopilot proposes
 * from. They were stored and never read.
 */
class TelemetryAggregatorTest {

    private final GatewayRequestLogRepository logs = mock(GatewayRequestLogRepository.class);
    private final AutopilotFeedbackRepository feedback = mock(AutopilotFeedbackRepository.class);
    private final ReplayVerificationReportRepository replays = mock(ReplayVerificationReportRepository.class);
    private final TelemetryAggregator aggregator = new TelemetryAggregator(logs, replays, feedback);

    private static GatewayRequestLogRepository.ProviderOutcome outcome(String provider, long ok, long failed) {
        return new GatewayRequestLogRepository.ProviderOutcome() {
            public String getProvider() { return provider; }
            public long getSuccesses() { return ok; }
            public long getFailures() { return failed; }
            public double getAvgLatency() { return 100; }
            public double getAvgCost() { return 0.001; }
        };
    }

    private static GatewayRequestLogEntity logged(String developer, String provider) {
        return new GatewayRequestLogEntity(developer, "auto", provider, "m", 0.1, "r", 10, 5, 0, true, 0);
    }

    @Test
    void aThumbsDownCountsAgainstTheModelThatAnswered() {
        when(logs.providerOutcomesForDeveloper("dev")).thenReturn(List.of(outcome("groq", 10, 0), outcome("gemini", 10, 0)));
        when(logs.findById(42L)).thenReturn(Optional.of(logged("dev", "groq")));
        when(logs.findById(43L)).thenReturn(Optional.of(logged("dev", "gemini")));
        when(feedback.findByDeveloperIdOrderByCreatedAtDesc("dev")).thenReturn(List.of(
                new AutopilotFeedbackEntity("dev", "req_42", -1, "wrong"),
                new AutopilotFeedbackEntity("dev", "43", 1, "good")));

        TelemetrySnapshot s = aggregator.snapshot("dev");

        assertThat(s.providerArms().get("groq").successes()).isEqualTo(9);
        assertThat(s.providerArms().get("groq").failures()).isEqualTo(1);
        assertThat(s.providerArms().get("gemini").successes()).isEqualTo(10);
        assertThat(s.successRate()).isEqualTo(19 / 20.0);
    }

    @Test
    void onlyTheLatestRatingOfAnAnswerCounts() {
        when(logs.providerOutcomesForDeveloper("dev")).thenReturn(List.of(outcome("groq", 10, 0)));
        when(logs.findById(42L)).thenReturn(Optional.of(logged("dev", "groq")));
        when(feedback.findByDeveloperIdOrderByCreatedAtDesc("dev")).thenReturn(List.of(
                new AutopilotFeedbackEntity("dev", "req_42", 1, "changed my mind"),
                new AutopilotFeedbackEntity("dev", "req_42", -1, "wrong")));

        assertThat(aggregator.snapshot("dev").providerArms().get("groq").failures()).isZero();
    }

    @Test
    void someoneElsesRequestIsNotOneOfYours() {
        when(logs.findById(42L)).thenReturn(Optional.of(logged("other", "groq")));
        when(logs.findFirstByTraceIdAndDeveloperId(anyString(), anyString())).thenReturn(Optional.empty());

        assertThat(aggregator.ownRequest("dev", "req_42")).isEmpty();
        assertThat(aggregator.ownRequest("dev", "a-trace-id")).isEmpty();
        assertThat(aggregator.ownRequest("other", "42")).isPresent();
    }
}
