package io.continuum.it;

import io.continuum.advisor.ConfigAdvisor;
import io.continuum.autopilot.AutopilotService;
import io.continuum.autopilot.model.AutopilotMode;
import io.continuum.autopilot.model.DeveloperProfile;
import io.continuum.cache.SemanticCacheService;
import io.continuum.chaos.ChaosMonkey;
import io.continuum.dag.ConsensusDagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The recommendation box's checks, against real settings. */
class AdvisorIT extends PostgresIT {

    @Autowired ConfigAdvisor advisor;
    @Autowired io.continuum.portal.PortalService portal;
    @Autowired AutopilotService autopilot;
    @Autowired ConsensusDagService dag;
    @Autowired SemanticCacheService cache;
    @Autowired ChaosMonkey chaos;

    private String dev;

    @BeforeEach
    void developer() {
        dev = portal.signup("Advisor IT", "adv-" + UUID.randomUUID() + "@example.com", "correct-horse-9").developerId();
    }

    @AfterEach
    void reset() {
        chaos.reset(dev);
    }

    @SuppressWarnings("unchecked")
    private List<String> ids() {
        Map<String, Object> out = advisor.advise(dev);
        return ((List<ConfigAdvisor.Advice>) out.get("advice")).stream().map(ConfigAdvisor.Advice::id).toList();
    }

    @Test
    void aFreshAccountHasNothingToFix() {
        assertThat(ids()).isEmpty();
    }

    @Test
    void aLatencyNoModelCanMeetIsReported() {
        autopilot.setProfile(dev, new DeveloperProfile("App", "fast", 0.02, 40, List.of("fake"), List.of(),
                AutopilotMode.LOW_LATENCY));
        assertThat(ids()).contains("latency-too-low");
    }

    @Test
    void verificationBypassingOtherFeaturesIsReported() {
        dag.setEnabled(dev, true);
        cache.setEnabled(dev, true);
        assertThat(ids()).contains("dag-bypasses");
    }

    @Test
    void anArmedChaosExperimentIsReported() {
        chaos.setProviderFailureRate(dev, 0.5);
        assertThat(ids()).contains("chaos-provider");
    }
}
