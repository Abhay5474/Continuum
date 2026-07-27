package io.continuum.api;

import io.continuum.persistence.entity.ProviderStatsEntity;
import io.continuum.persistence.entity.RoutingDecisionEntity;
import io.continuum.persistence.repository.RoutingDecisionRepository;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import io.continuum.portal.RequestScope;
import io.continuum.routing.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Extension 2 — AI-Aware Model Router API. Lets you toggle smart routing, inspect
 * live provider stats, and preview how a task would be routed (with full scoring
 * rationale) before running it.
 */
@RestController
@RequestMapping("/api/routing")
public class RoutingController {

    private final ModelRoutingState state;
    private final ProviderSelectionEngine engine;
    private final ProviderMetrics metrics;
    private final RoutingDecisionRepository decisions;
    private final io.continuum.autopilot.engine.ContextualBanditEngine contextualBandit;
    private final RoutingStrategyService routingStrategy;

    public RoutingController(ModelRoutingState state, ProviderSelectionEngine engine,
                             ProviderMetrics metrics, RoutingDecisionRepository decisions,
                             io.continuum.autopilot.engine.ContextualBanditEngine contextualBandit,
                             RoutingStrategyService routingStrategy) {
        this.state = state;
        this.engine = engine;
        this.metrics = metrics;
        this.decisions = decisions;
        this.contextualBandit = contextualBandit;
        this.routingStrategy = routingStrategy;
    }

    /**
     * V8 — the learned contextual + non-stationary bandit's per-context posteriors,
     * built from real gateway outcomes. Under the LEARNED strategy these
     * posteriors are what actually choose the provider.
     */
    @GetMapping("/bandit")
    public Map<String, Object> bandit() {
        return contextualBandit.snapshot();
    }

    /**
     * Context-aware provider ranking: how the non-stationary bandit would order
     * these providers for a request of the given complexity, right now.
     */
    @GetMapping("/bandit/suggest")
    public Map<String, Object> banditSuggest(@RequestParam double complexity,
                                             @RequestParam List<String> providers,
                                             @RequestParam(defaultValue = "0.6") double wQuality,
                                             @RequestParam(defaultValue = "0.2") double wCost,
                                             @RequestParam(defaultValue = "0.2") double wLatency) {
        var ctx = io.continuum.autopilot.engine.ContextualBanditEngine.Context.ofComplexity(complexity);
        var ranked = contextualBandit.rank(ctx, providers, wQuality, wCost, wLatency);
        return Map.of("context", ctx.name(), "ranking", ranked);
    }

    @GetMapping("/state")
    public Map<String, Object> getState() {
        return Map.of(
                "enabled", state.isEnabled(),
                "mode", state.getMode(),
                // The strategy in force right now, and the one configured — they
                // differ while routing is switched off, and conflating them is
                // how the console previously showed a setting that did nothing.
                "strategy", state.getStrategy(),
                "configuredStrategy", state.getConfiguredStrategy(),
                "hedgingOnGateway", true);
    }

    /**
     * Routing mode is engine-wide configuration, so changing it is the operator's
     * call. A tenant flipping the mode would silently re-route every other
     * tenant's traffic; reads stay open so a developer can see what they are on.
     */
    @PostMapping("/enable")
    public Map<String, Object> enable(@RequestParam(defaultValue = "true") boolean enabled,
                                      @RequestParam(required = false) RoutingMode mode,
                                      HttpServletRequest req) {
        requireOperator(req);
        state.setEnabled(enabled);
        if (mode != null) {
            state.setMode(mode);
        }
        return getState();
    }

    /**
     * Which strategy orders providers. Engine-wide, so operator-only.
     *
     * <p>{@code LEARNED} is the one that matters: it is what finally lets the
     * contextual bandit act on what it has been observing all along.
     */
    @PostMapping("/strategy")
    public Map<String, Object> setStrategy(@RequestParam ModelRoutingState.Strategy strategy,
                                           HttpServletRequest req) {
        requireOperator(req);
        state.setStrategy(strategy);
        return getState();
    }

    /**
     * Learned routing against its own baseline.
     *
     * <p>Scoped to the caller, because one tenant's routing outcomes are not
     * another's business; the operator sees the engine-wide picture.
     */
    @GetMapping("/comparison")
    public Map<String, Object> comparison(@RequestParam(defaultValue = "500") int limit,
                                          HttpServletRequest req) {
        String dev = RequestScope.isOperator(req) ? null : RequestScope.requireDeveloper(req);
        return routingStrategy.comparison(dev, Math.max(1, Math.min(2000, limit)));
    }

    @PostMapping("/mode")
    public Map<String, Object> setMode(@RequestParam RoutingMode mode, HttpServletRequest req) {
        requireOperator(req);
        state.setMode(mode);
        return getState();
    }

    @GetMapping("/providers")
    public List<ProviderStatsEntity> providers() {
        return metrics.all();
    }

    /** Preview routing for a sample task — real scoring against current runtime stats. */
    @PostMapping("/select")
    public SelectionResult select(@RequestBody SelectRequest req) {
        RoutingMode mode = req.mode() != null ? req.mode() : state.getMode();
        LlmRequest request = new LlmRequest(null,
                List.of(Message.system(req.systemPrompt() == null ? "" : req.systemPrompt()),
                        Message.user(req.userPrompt() == null ? "" : req.userPrompt())),
                req.maxTokens() != null ? req.maxTokens() : 512, 0.2);
        return engine.select(request, RoutingPolicy.of(mode));
    }

    /** The caller's own routing history; engine-wide only for the operator. */
    @GetMapping("/decisions")
    public List<RoutingDecisionEntity> decisions(@RequestParam(defaultValue = "100") int limit,
                                                 HttpServletRequest req) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(500, limit)));
        return (RequestScope.isOperator(req)
                ? decisions.findAllByOrderByCreatedAtDesc(page)
                : decisions.findByDeveloperIdOrderByCreatedAtDesc(RequestScope.requireDeveloper(req), page))
                .getContent();
    }

    private static void requireOperator(HttpServletRequest req) {
        if (!RequestScope.isOperator(req)) {
            throw new RequestScope.ForbiddenException();
        }
    }

    public record SelectRequest(String systemPrompt, String userPrompt, RoutingMode mode, Integer maxTokens) {
    }
}
