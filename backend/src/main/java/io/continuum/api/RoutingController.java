package io.continuum.api;

import io.continuum.persistence.entity.ProviderStatsEntity;
import io.continuum.persistence.entity.RoutingDecisionEntity;
import io.continuum.persistence.repository.RoutingDecisionRepository;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.Message;
import io.continuum.routing.*;
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

    public RoutingController(ModelRoutingState state, ProviderSelectionEngine engine,
                             ProviderMetrics metrics, RoutingDecisionRepository decisions,
                             io.continuum.autopilot.engine.ContextualBanditEngine contextualBandit) {
        this.state = state;
        this.engine = engine;
        this.metrics = metrics;
        this.decisions = decisions;
        this.contextualBandit = contextualBandit;
    }

    /**
     * V8 — the learned contextual + non-stationary bandit's per-context posteriors,
     * built from real gateway outcomes (record-only; does not change routing).
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
        return Map.of("enabled", state.isEnabled(), "mode", state.getMode());
    }

    @PostMapping("/enable")
    public Map<String, Object> enable(@RequestParam(defaultValue = "true") boolean enabled,
                                      @RequestParam(required = false) RoutingMode mode) {
        state.setEnabled(enabled);
        if (mode != null) {
            state.setMode(mode);
        }
        return getState();
    }

    @PostMapping("/mode")
    public Map<String, Object> setMode(@RequestParam RoutingMode mode) {
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

    @GetMapping("/decisions")
    public List<RoutingDecisionEntity> decisions(@RequestParam(defaultValue = "100") int limit) {
        return decisions.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)).getContent();
    }

    public record SelectRequest(String systemPrompt, String userPrompt, RoutingMode mode, Integer maxTokens) {
    }
}
