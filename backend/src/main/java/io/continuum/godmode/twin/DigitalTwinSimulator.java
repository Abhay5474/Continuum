package io.continuum.godmode.twin;

import io.continuum.aichaos.injectors.HallucinationInjector;
import io.continuum.autopilot.PolicyBundleService;
import io.continuum.autopilot.engine.CanaryEvaluator;
import io.continuum.autopilot.model.PolicyBundle;
import io.continuum.autopilot.stats.BetaDistribution;
import io.continuum.common.Json;
import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.entity.GodModeSimulationEntity;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import io.continuum.persistence.repository.GodModeSimulationRepository;
import io.continuum.semantic.ReplayVerificationPolicy;
import io.continuum.semantic.SemanticComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The counterfactual "digital twin": off-policy evaluation of a candidate
 * policy bundle against the developer's REAL historical gateway traffic —
 * before the candidate ever touches production requests.
 *
 * Reuses, never reinvents:
 * <ul>
 *   <li>Provider behavior models are Beta posteriors ({@link BetaDistribution},
 *       the V4 stats machinery) fitted from measured per-provider outcomes in
 *       {@code gateway_requests} — no invented numbers.</li>
 *   <li>Verdicts come from the V4 {@link CanaryEvaluator}: the same Bayesian
 *       promote/rollback rules the live canary applies, applied to simulated
 *       outcome counts.</li>
 *   <li>Synthetic-failure scenarios reuse the V2 chaos injectors offline
 *       (e.g. {@link HallucinationInjector}) and score detection with the V2
 *       {@link SemanticComparator} — real corruption, real detection scoring,
 *       zero live traffic.</li>
 * </ul>
 *
 * Deterministic: the RNG is seeded from (developer, bundle, scenario), so a
 * re-run of the same simulation yields the same verdict.
 */
@Service
public class DigitalTwinSimulator {

    private static final Logger log = LoggerFactory.getLogger(DigitalTwinSimulator.class);
    private static final int MAX_HISTORY = 500;
    private static final int BOOTSTRAP_ROUNDS = 4; // each request replayed N times

    public enum Scenario { HISTORICAL_REPLAY, PROVIDER_OUTAGE, HALLUCINATION_STORM }

    private final GatewayRequestLogRepository requests;
    private final GodModeSimulationRepository simulations;
    private final PolicyBundleService bundles;
    private final CanaryEvaluator canaryEvaluator;
    private final HallucinationInjector hallucination;
    private final SemanticComparator comparator;
    private final Json json;

    public DigitalTwinSimulator(GatewayRequestLogRepository requests,
                                GodModeSimulationRepository simulations,
                                PolicyBundleService bundles,
                                CanaryEvaluator canaryEvaluator,
                                HallucinationInjector hallucination,
                                SemanticComparator comparator,
                                Json json) {
        this.requests = requests;
        this.simulations = simulations;
        this.bundles = bundles;
        this.canaryEvaluator = canaryEvaluator;
        this.hallucination = hallucination;
        this.comparator = comparator;
        this.json = json;
    }

    /** Simulate a candidate bundle vs the active baseline over historical traffic. */
    @Transactional
    public GodModeSimulationEntity simulate(String developerId, Long candidateBundleId,
                                            Long baselineBundleId, Scenario scenario) {
        PolicyBundle candidate = bundleOf(candidateBundleId);
        PolicyBundle baseline = bundleOf(baselineBundleId);
        List<GatewayRequestLogEntity> history = requests
                .findByDeveloperIdOrderByCreatedAtDesc(developerId, PageRequest.of(0, MAX_HISTORY))
                .getContent();

        Random rng = new Random(seed(developerId, candidateBundleId, scenario));
        Map<String, ProviderModel> providers = fitProviders(history);

        CanaryEvaluator.BundleStats candStats;
        CanaryEvaluator.BundleStats baseStats;
        if (scenario == Scenario.HALLUCINATION_STORM) {
            candStats = hallucinationStorm(candidate, rng);
            baseStats = hallucinationStorm(baseline, rng);
        } else {
            if (scenario == Scenario.PROVIDER_OUTAGE && !providers.isEmpty()) {
                // Degrade the most-used provider: stress the fallback strategy.
                String mostUsed = providers.entrySet().stream()
                        .max(java.util.Comparator.comparingLong(e -> e.getValue().calls))
                        .map(Map.Entry::getKey).orElse(null);
                if (mostUsed != null) {
                    providers.get(mostUsed).outage = true;
                }
            }
            candStats = replay(history, candidate, providers, rng);
            baseStats = replay(history, baseline, providers, rng);
        }

        CanaryEvaluator.Result verdict = canaryEvaluator.evaluate(candStats, baseStats);
        GodModeSimulationEntity sim = simulations.save(new GodModeSimulationEntity(
                developerId, candidateBundleId, scenario.name(),
                history.size() * BOOTSTRAP_ROUNDS,
                json.write(baseStats), json.write(candStats),
                verdict.verdict().name(), verdict.reason(), verdict.confidence()));
        log.info("Digital twin {} for {}: {} ({})", scenario, developerId,
                verdict.verdict(), verdict.reason());
        return sim;
    }

    public List<GodModeSimulationEntity> recent(String developerId) {
        return simulations.findTop50ByDeveloperIdOrderByCreatedAtDesc(developerId);
    }

    // ---- historical bootstrap replay ----

    private CanaryEvaluator.BundleStats replay(List<GatewayRequestLogEntity> history,
                                               PolicyBundle bundle,
                                               Map<String, ProviderModel> providers,
                                               Random rng) {
        long successes = 0;
        long failures = 0;
        double latencySum = 0;
        double costSum = 0;
        long n = 0;

        List<String> order = orderFor(bundle, providers);
        int maxAttempts = Math.max(1, Math.min(order.size(), 1 + Math.max(0, bundle.maxRetries())));

        for (GatewayRequestLogEntity ignoredRequest : history) {
            for (int round = 0; round < BOOTSTRAP_ROUNDS; round++) {
                double latency = 0;
                double cost = 0;
                boolean ok = false;
                for (int a = 0; a < maxAttempts && !ok; a++) {
                    ProviderModel p = providers.get(order.get(a));
                    if (p == null) {
                        continue;
                    }
                    latency += p.sampleLatency(rng);
                    double successProb = p.posterior().sample(rng) * (p.outage ? 0.1 : 1.0);
                    if (rng.nextDouble() < successProb) {
                        cost += p.avgCost;
                        ok = true;
                    }
                }
                // The bundle's own budgets are part of the policy being evaluated.
                if (ok && bundle.latencyCapMs() > 0 && latency > bundle.latencyCapMs()) {
                    ok = false;
                }
                if (ok && bundle.costCapUsd() > 0 && cost > bundle.costCapUsd()) {
                    ok = false;
                }
                if (ok) {
                    successes++;
                } else {
                    failures++;
                }
                latencySum += latency;
                costSum += cost;
                n++;
            }
        }
        return new CanaryEvaluator.BundleStats(successes, failures,
                n == 0 ? 0 : latencySum / n, n == 0 ? 0 : costSum / n);
    }

    // ---- synthetic-failure scenario: V2 hallucination injection, scored by V2 semantics ----

    /**
     * Corrupts realistic decision outputs with the V2 {@link HallucinationInjector}
     * and asks: would this bundle's verification threshold have CAUGHT the
     * corruption? Detection (per V2 semantics: comparison score below the
     * bundle's pass bar) counts as a success; an undetected reversal is a failure.
     */
    private CanaryEvaluator.BundleStats hallucinationStorm(PolicyBundle bundle, Random rng) {
        String[] corpus = {
                "Decision: the transaction is approved because all risk checks passed.",
                "The claim is valid and the refund should be issued to the customer.",
                "Analysis complete: the deployment is safe to promote to production.",
                "Recommendation: accept the loan application, credit score is sufficient.",
                "The test suite passed, the release is approved for rollout."
        };
        long detected = 0;
        long missed = 0;
        double thresh = bundle.verificationPassThreshold();
        ReplayVerificationPolicy policy = ReplayVerificationPolicy.defaults();
        for (int i = 0; i < 40; i++) {
            String original = corpus[rng.nextInt(corpus.length)];
            String corrupted = hallucination.corrupt(original);
            double score = comparator.compare(original, corrupted, policy).overallScore();
            if (score < thresh) {
                detected++;
            } else {
                missed++;
            }
        }
        return new CanaryEvaluator.BundleStats(detected, missed, 0, 0);
    }

    // ---- provider behavior models fitted from real telemetry ----

    private Map<String, ProviderModel> fitProviders(List<GatewayRequestLogEntity> history) {
        Map<String, ProviderModel> out = new HashMap<>();
        for (GatewayRequestLogEntity r : history) {
            String p = r.getChosenProvider();
            if (p == null) {
                continue;
            }
            ProviderModel m = out.computeIfAbsent(p, k -> new ProviderModel());
            m.calls++;
            if (r.isSuccess()) {
                m.successes++;
                m.latencySum += r.getLatencyMs();
                m.costSum += r.getCostUsd();
            } else {
                m.failures++;
            }
        }
        for (ProviderModel m : out.values()) {
            long ok = Math.max(1, m.successes);
            m.avgLatencyMs = m.latencySum / ok;
            m.avgCost = m.costSum / ok;
        }
        return out;
    }

    private List<String> orderFor(PolicyBundle bundle, Map<String, ProviderModel> providers) {
        List<String> order = new ArrayList<>();
        if (bundle.providerOrder() != null) {
            for (String p : bundle.providerOrder()) {
                if (providers.containsKey(p)) {
                    order.add(p);
                }
            }
        }
        providers.keySet().stream()
                .filter(p -> !order.contains(p))
                .sorted()
                .forEach(order::add);
        return order.isEmpty() ? List.of("mock") : order;
    }

    private PolicyBundle bundleOf(Long id) {
        if (id != null) {
            var e = bundles.entity(id).orElse(null);
            if (e != null) {
                return bundles.parse(e);
            }
        }
        return PolicyBundle.defaultFor(io.continuum.autopilot.model.AutopilotMode.BALANCED,
                List.of(), 0, 0);
    }

    private static long seed(String developerId, Long bundleId, Scenario scenario) {
        return (developerId + ":" + bundleId + ":" + scenario).hashCode();
    }

    private static final class ProviderModel {
        long calls;
        long successes;
        long failures;
        double latencySum;
        double costSum;
        double avgLatencyMs = 500;
        double avgCost = 0.0001;
        boolean outage;

        BetaDistribution posterior() {
            return BetaDistribution.fromCounts(successes, failures);
        }

        double sampleLatency(Random rng) {
            // Log-normal-ish jitter around the measured mean (Tail-at-Scale shape).
            return Math.max(1, avgLatencyMs * Math.exp(rng.nextGaussian() * 0.3));
        }
    }
}
