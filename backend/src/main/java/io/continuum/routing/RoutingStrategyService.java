package io.continuum.routing;

import io.continuum.autopilot.engine.ContextualBanditEngine;
import io.continuum.persistence.entity.RoutingStrategyDecisionEntity;
import io.continuum.persistence.repository.RoutingStrategyDecisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides the provider order for a gateway request, and records the
 * counterfactual so the decision can be judged afterwards.
 *
 * <p>Three things were wrong before this class existed. The gateway never read
 * the routing on/off switch, so turning routing off changed nothing. The
 * contextual bandit was fed the outcome of every request and was never asked for
 * an opinion — a complete Thompson sampler whose learning went nowhere. And
 * there was no way to tell whether any of the routing machinery was better than
 * the static order it replaced.
 *
 * <p>So every decision now records both the provider the active strategy chose
 * and the provider the heuristic scorer <em>would</em> have chosen. Where those
 * disagree, the outcome is a measurement rather than an assertion: it is
 * possible to say "on the 240 requests where learning changed the answer, the
 * success rate was 4 points higher and the cost 18% lower", or to discover that
 * it was worse and turn it off.
 *
 * <p>Exploration is capped. A bandit that explores freely is correct in the
 * limit and unpleasant in production, so the sampled order is only allowed to
 * override the heuristic's first choice on a bounded fraction of traffic.
 */
@Service
public class RoutingStrategyService {

    private static final Logger log = LoggerFactory.getLogger(RoutingStrategyService.class);

    /** Utility weights used when asking the bandit to rank. */
    private static final double W_QUALITY = 0.6;
    private static final double W_COST = 0.25;
    private static final double W_LATENCY = 0.15;

    /**
     * An arm needs at least this many observations before it is trusted enough
     * to displace the heuristic. Below it the posterior is mostly prior, and
     * "learned" routing would really be a coin toss wearing a lab coat.
     */
    private static final long MIN_OBSERVATIONS = 12;

    private final ModelRoutingState state;
    private final ContextualBanditEngine bandit;
    private final RoutingStrategyDecisionRepository decisions;

    public RoutingStrategyService(ModelRoutingState state, ContextualBanditEngine bandit,
                                  RoutingStrategyDecisionRepository decisions) {
        this.state = state;
        this.bandit = bandit;
        this.decisions = decisions;
    }

    /**
     * The chosen order, plus everything needed to explain and later audit it.
     *
     * @param order       providers, best first
     * @param baseline    what the heuristic would have put first
     * @param explored    whether the bandit picked an under-observed arm on purpose
     */
    public record Decision(List<String> order, ModelRoutingState.Strategy strategy, String baseline,
                           boolean explored, String explanation) {

        public String chosen() {
            return order.isEmpty() ? null : order.get(0);
        }
    }

    /**
     * Orders providers for one request.
     *
     * @param heuristicOrder what {@link ProviderSelectionEngine} produced
     * @param available      the providers actually reachable right now
     */
    public Decision decide(List<String> heuristicOrder, List<String> available, double complexity) {
        String baseline = heuristicOrder.isEmpty() ? null : heuristicOrder.get(0);
        ModelRoutingState.Strategy strategy = state.getStrategy();

        if (strategy == ModelRoutingState.Strategy.STATIC) {
            // Routing off: availability order, exactly as V1 behaved. The switch
            // now means something on the gateway path.
            return new Decision(List.copyOf(available), strategy, baseline, false,
                    "routing off — static availability order");
        }
        if (strategy == ModelRoutingState.Strategy.HEURISTIC || heuristicOrder.isEmpty()) {
            return new Decision(List.copyOf(heuristicOrder), strategy, baseline, false,
                    "cost/latency/quality scorer over provider statistics");
        }

        // LEARNED: ask the bandit, but only let it win where it has seen enough.
        try {
            ContextualBanditEngine.Context ctx = ContextualBanditEngine.Context.ofComplexity(complexity);
            List<ContextualBanditEngine.Ranked> ranked =
                    bandit.rank(ctx, heuristicOrder, W_QUALITY, W_COST, W_LATENCY);
            if (ranked.isEmpty()) {
                return new Decision(List.copyOf(heuristicOrder), strategy, baseline, false,
                        "bandit had no arms — fell back to the scorer");
            }
            ContextualBanditEngine.Ranked top = ranked.get(0);
            boolean confident = top.observations() >= MIN_OBSERVATIONS;
            if (!confident) {
                // Deliberate, bounded exploration: follow the sampler even though
                // the arm is thin, because that is the only way it gets thicker.
                boolean explore = Math.random() < 0.15;
                if (!explore) {
                    return new Decision(List.copyOf(heuristicOrder), strategy, baseline, false,
                            String.format("bandit prefers %s but has only %d observations — exploiting the scorer",
                                    top.provider(), top.observations()));
                }
                List<String> order = orderFrom(ranked);
                return new Decision(order, strategy, baseline, true,
                        String.format("exploring %s (%d observations) to widen the posterior",
                                top.provider(), top.observations()));
            }
            List<String> order = orderFrom(ranked);
            return new Decision(order, strategy, baseline, false,
                    String.format("bandit chose %s — %s context, posterior mean %.2f over %d observations",
                            top.provider(), ctx, top.meanSuccess(), top.observations()));
        } catch (Exception e) {
            // Routing must never fail a request; the scorer is always a valid answer.
            log.warn("Learned routing failed, falling back to the scorer: {}", e.getMessage());
            return new Decision(List.copyOf(heuristicOrder), strategy, baseline, false,
                    "learned routing errored — fell back to the scorer");
        }
    }

    /** Records the outcome against the decision. Never throws. */
    public void record(String developerId, Decision decision, double complexity,
                       String actualProvider, boolean success, long latencyMs, double cost) {
        if (developerId == null || actualProvider == null) {
            return;
        }
        try {
            decisions.save(new RoutingStrategyDecisionEntity(
                    developerId,
                    decision.strategy().name(),
                    ContextualBanditEngine.Context.ofComplexity(complexity).name(),
                    complexity,
                    actualProvider,
                    decision.baseline(),
                    decision.explored(),
                    success,
                    latencyMs,
                    cost));
        } catch (Exception e) {
            log.debug("Could not record routing decision: {}", e.getMessage());
        }
    }

    /**
     * The comparison that justifies (or condemns) learned routing.
     *
     * <p>Deliberately reports the divergent subset separately. Aggregate success
     * rate across all traffic is dominated by the requests where both strategies
     * agreed, which tells you nothing about whether learning helped.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> comparison(String developerId, int limit) {
        List<RoutingStrategyDecisionEntity> rows = developerId == null
                ? decisions.recentAll(PageRequest.of(0, limit))
                : decisions.recentFor(developerId, PageRequest.of(0, limit));

        long total = rows.size();
        long diverged = rows.stream().filter(RoutingStrategyDecisionEntity::isDiverged).count();
        long explored = rows.stream().filter(RoutingStrategyDecisionEntity::isExplored).count();

        List<RoutingStrategyDecisionEntity> div = rows.stream()
                .filter(RoutingStrategyDecisionEntity::isDiverged).toList();
        List<RoutingStrategyDecisionEntity> same = rows.stream()
                .filter(r -> !r.isDiverged()).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", state.getStrategy().name());
        out.put("configuredStrategy", state.getConfiguredStrategy().name());
        out.put("enabled", state.isEnabled());
        out.put("decisions", total);
        out.put("diverged", diverged);
        out.put("explored", explored);
        out.put("divergenceRate", total == 0 ? 0.0 : (double) diverged / total);
        out.put("whenDiverged", summarise(div));
        out.put("whenAgreed", summarise(same));
        out.put("recent", rows.stream().limit(30).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("strategy", r.getStrategy());
            m.put("context", r.getContextBucket());
            m.put("chosen", r.getChosenProvider());
            m.put("baseline", r.getBaselineProvider());
            m.put("diverged", r.isDiverged());
            m.put("explored", r.isExplored());
            m.put("success", r.isSuccess());
            m.put("latencyMs", r.getLatencyMs());
            m.put("cost", r.getCost());
            m.put("createdAt", r.getCreatedAt());
            return m;
        }).toList());
        return out;
    }

    private static Map<String, Object> summarise(List<RoutingStrategyDecisionEntity> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", rows.size());
        if (rows.isEmpty()) {
            m.put("successRate", null);
            m.put("avgLatencyMs", null);
            m.put("avgCost", null);
            return m;
        }
        m.put("successRate", rows.stream().filter(RoutingStrategyDecisionEntity::isSuccess).count()
                / (double) rows.size());
        m.put("avgLatencyMs", rows.stream().mapToLong(RoutingStrategyDecisionEntity::getLatencyMs).average().orElse(0));
        m.put("avgCost", rows.stream().mapToDouble(RoutingStrategyDecisionEntity::getCost).average().orElse(0));
        return m;
    }

    private static List<String> orderFrom(List<ContextualBanditEngine.Ranked> ranked) {
        List<String> order = new ArrayList<>(ranked.size());
        for (ContextualBanditEngine.Ranked r : ranked) {
            order.add(r.provider());
        }
        return order;
    }
}
