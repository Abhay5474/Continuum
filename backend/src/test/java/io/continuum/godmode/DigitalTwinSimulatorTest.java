package io.continuum.godmode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.continuum.aichaos.injectors.HallucinationInjector;
import io.continuum.autopilot.PolicyBundleService;
import io.continuum.autopilot.engine.CanaryEvaluator;
import io.continuum.autopilot.model.PolicyBundle;
import io.continuum.common.Json;
import io.continuum.godmode.twin.DigitalTwinSimulator;
import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.entity.GodModeSimulationEntity;
import io.continuum.persistence.entity.PolicyBundleEntity;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import io.continuum.persistence.repository.GodModeSimulationRepository;
import io.continuum.semantic.SemanticComparator;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The digital twin: off-policy evaluation of candidate bundles against real
 * historical traffic, judged by the SAME V4 CanaryEvaluator rules as a live
 * canary — deterministic given the seeded scenario.
 */
class DigitalTwinSimulatorTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Json json = new Json(mapper);

    private final GatewayRequestLogRepository requests = mock(GatewayRequestLogRepository.class);
    private final GodModeSimulationRepository sims = mock(GodModeSimulationRepository.class);
    private final PolicyBundleService bundles = mock(PolicyBundleService.class);

    private final DigitalTwinSimulator twin = new DigitalTwinSimulator(
            requests, sims, bundles, new CanaryEvaluator(),
            new HallucinationInjector(), new SemanticComparator(mapper), json);

    private void givenHistory(List<GatewayRequestLogEntity> history) {
        org.springframework.data.domain.Page<GatewayRequestLogEntity> page =
                new org.springframework.data.domain.PageImpl<>(history);
        when(requests.findByDeveloperIdOrderByCreatedAtDesc(anyString(), any())).thenReturn(page);
        when(sims.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void givenBundle(long id, PolicyBundle bundle) {
        PolicyBundleEntity entity = mock(PolicyBundleEntity.class);
        when(bundles.entity(eq(id))).thenReturn(Optional.of(entity));
        when(bundles.parse(entity)).thenReturn(bundle);
    }

    /** History: provider "reliable" ~always succeeds; "flaky" ~always fails. */
    private List<GatewayRequestLogEntity> mixedHistory() {
        List<GatewayRequestLogEntity> h = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            h.add(new GatewayRequestLogEntity("dev-1", "auto", "reliable", "m1",
                    0.4, "test", 300, 100, 0.0002, true, 0));
        }
        for (int i = 0; i < 40; i++) {
            h.add(new GatewayRequestLogEntity("dev-1", "auto", "flaky", "m2",
                    0.4, "test", 900, 0, 0.0, false, 1));
        }
        return h;
    }

    private PolicyBundle bundleWithOrder(List<String> order) {
        return new PolicyBundle("BALANCED", order, 2, 2500, 2, 0.35, 10,
                0.05, 30000, 50, 0.65, 60);
    }

    @Test
    void candidatePreferringTheFlakyProviderIsRolledBackOffline() {
        givenHistory(mixedHistory());
        givenBundle(1L, bundleWithOrder(List.of("reliable", "flaky"))); // baseline
        // Candidate routes flaky-first AND allows no failover (maxRetries 0).
        givenBundle(2L, new PolicyBundle("BALANCED", List.of("flaky"), 0, 2500, 2,
                0.35, 10, 0.05, 30000, 50, 0.65, 60));

        GodModeSimulationEntity sim = twin.simulate("dev-1", 2L, 1L,
                DigitalTwinSimulator.Scenario.HISTORICAL_REPLAY);

        assertEquals("ROLLBACK", sim.getVerdict(),
                "an offline replay must catch the regression before live traffic: " + sim.getReason());
        assertTrue(sim.getReplayedRequests() > 0);
    }

    @Test
    void candidateMatchingTheBaselineIsNeverVetoed() {
        givenHistory(mixedHistory());
        PolicyBundle good = bundleWithOrder(List.of("reliable", "flaky"));
        givenBundle(1L, good);
        givenBundle(2L, good);

        GodModeSimulationEntity sim = twin.simulate("dev-1", 2L, 1L,
                DigitalTwinSimulator.Scenario.HISTORICAL_REPLAY);

        assertNotEquals("ROLLBACK", sim.getVerdict(),
                "identical policies must not produce a confident regression: " + sim.getReason());
    }

    @Test
    void twinIsDeterministicForTheSameSeedInputs() {
        givenHistory(mixedHistory());
        givenBundle(1L, bundleWithOrder(List.of("reliable", "flaky")));
        givenBundle(2L, bundleWithOrder(List.of("flaky", "reliable")));

        GodModeSimulationEntity a = twin.simulate("dev-1", 2L, 1L,
                DigitalTwinSimulator.Scenario.HISTORICAL_REPLAY);
        GodModeSimulationEntity b = twin.simulate("dev-1", 2L, 1L,
                DigitalTwinSimulator.Scenario.HISTORICAL_REPLAY);

        assertEquals(a.getVerdict(), b.getVerdict());
        assertEquals(a.getCandidateMetricsJson(), b.getCandidateMetricsJson(),
                "seeded replay must be reproducible");
    }

    @Test
    void hallucinationStormScoresDetectionWithRealV2Injection() {
        givenHistory(List.of());
        // Strict verification bar (0.9): hallucinated reversals score below it → detected.
        givenBundle(2L, new PolicyBundle("BALANCED", List.of(), 2, 2500, 2,
                0.35, 10, 0.05, 30000, 50, 0.90, 60));
        // Absurdly lax bar (0.01): nothing scores below it → nothing detected.
        givenBundle(1L, new PolicyBundle("BALANCED", List.of(), 2, 2500, 2,
                0.35, 10, 0.05, 30000, 50, 0.01, 60));

        GodModeSimulationEntity sim = twin.simulate("dev-1", 2L, 1L,
                DigitalTwinSimulator.Scenario.HALLUCINATION_STORM);

        var cand = json.read(sim.getCandidateMetricsJson(), CanaryEvaluator.BundleStats.class);
        var base = json.read(sim.getBaselineMetricsJson(), CanaryEvaluator.BundleStats.class);
        assertTrue(cand.successes() > base.successes(),
                "a strict verification threshold must detect more V2-injected hallucinations ("
                        + cand.successes() + " vs " + base.successes() + ")");
    }
}
