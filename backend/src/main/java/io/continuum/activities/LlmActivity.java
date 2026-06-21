package io.continuum.activities;

import io.continuum.aichaos.AiChaosEngine;
import io.continuum.chaos.ChaosMonkey;
import io.continuum.common.Json;
import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import io.continuum.hedging.HedgedResult;
import io.continuum.hedging.HedgingService;
import io.continuum.persistence.entity.RoutingDecisionEntity;
import io.continuum.persistence.repository.RoutingDecisionRepository;
import io.continuum.provider.ProviderRouter;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import io.continuum.routing.ModelRoutingState;
import io.continuum.routing.ProviderSelectionEngine;
import io.continuum.routing.SelectionResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Invokes an LLM as a durable activity.
 *
 * V1 behavior (single provider chain via {@link ProviderRouter}, automatic
 * failover, result recorded as {@code ACTIVITY_COMPLETED} so replay never
 * re-calls the model) is the default and unchanged.
 *
 * When the V2 features are switched on, this activity additionally:
 *  - selects an optimal provider chain via the AI-aware router (Extension 2),
 *  - races providers with tail-latency hedging (Extension 4),
 *  - has AI failures injected around the call (Extension 3).
 * Each of these is OFF by default; with all off, the code path is identical to V1.
 */
@Component
public class LlmActivity implements Activity {

    public static final String TYPE = "llm.complete";

    private final ProviderRouter router;
    private final ChaosMonkey chaos;
    private final ModelRoutingState routingState;
    private final ProviderSelectionEngine selectionEngine;
    private final RoutingDecisionRepository routingDecisions;
    private final AiChaosEngine aiChaos;
    private final HedgingService hedging;
    private final Json json;

    public LlmActivity(ProviderRouter router, ChaosMonkey chaos, ModelRoutingState routingState,
                       ProviderSelectionEngine selectionEngine, RoutingDecisionRepository routingDecisions,
                       AiChaosEngine aiChaos, HedgingService hedging, Json json) {
        this.router = router;
        this.chaos = chaos;
        this.routingState = routingState;
        this.selectionEngine = selectionEngine;
        this.routingDecisions = routingDecisions;
        this.aiChaos = aiChaos;
        this.hedging = hedging;
        this.json = json;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object execute(String inputJson, ActivityContext ctx) {
        chaos.maybeFailActivity(TYPE);

        Input in = ctx.input(inputJson, Input.class);
        LlmRequest request = toRequest(in);
        long seq = parseSeq(ctx.idempotencyKey());

        // Extension 3: inject AI failures into the request (no-op unless active).
        if (aiChaos.isActive()) {
            request = aiChaos.applyToRequest(request, ctx.workflowId(), seq);
        }

        LlmResponse response = invoke(request, ctx.workflowId());

        // Extension 3: inject AI failures into the response (no-op unless active).
        if (aiChaos.isActive()) {
            response = aiChaos.applyToResponse(response, ctx.workflowId(), seq);
        }

        double cost = router.estimateCost(response.provider(), response.model(),
                response.promptTokens(), response.completionTokens());
        ctx.recordCost(response.provider(), response.model(),
                response.promptTokens(), response.completionTokens(), cost);

        return new Output(response.content(), response.provider(), response.model(),
                response.promptTokens(), response.completionTokens(), cost);
    }

    /** Chooses how to call the model based on which V2 features are enabled. */
    private LlmResponse invoke(LlmRequest request, String workflowId) {
        // Default V1 path: static failover order, no routing, no hedging.
        if (!routingState.isEnabled() && !hedging.isEnabled()) {
            return router.complete(request);
        }

        // Extension 2: pick an optimal provider chain before execution.
        List<String> chain;
        if (routingState.isEnabled()) {
            SelectionResult selection = selectionEngine.select(request, routingState.currentPolicy());
            chain = selection.chosenChain();
            recordRoutingDecision(workflowId, selection);
        } else {
            chain = router.availableChain();
        }

        // Extension 4: race the chain with tail-latency hedging.
        if (hedging.isEnabled()) {
            HedgedResult hedged = hedging.execute(request, chain);
            return hedged.response();
        }
        return router.complete(request, chain);
    }

    private void recordRoutingDecision(String workflowId, SelectionResult selection) {
        try {
            routingDecisions.save(new RoutingDecisionEntity(
                    workflowId, selection.mode().name(), selection.complexity(),
                    selection.chosenProvider(), String.join(",", selection.chosenChain()),
                    json.write(selection.scores())));
        } catch (Exception ignored) {
            // Observability must never break the call.
        }
    }

    private long parseSeq(String idempotencyKey) {
        int idx = idempotencyKey.lastIndexOf(':');
        try {
            return idx >= 0 ? Long.parseLong(idempotencyKey.substring(idx + 1)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Builds the canonical {@link LlmRequest} from an activity input. Shared with
     * the semantic replay verifier so a historical call can be regenerated with
     * byte-identical request construction.
     */
    public static LlmRequest toRequest(Input in) {
        List<Message> messages = new ArrayList<>();
        if (in.systemPrompt() != null && !in.systemPrompt().isBlank()) {
            messages.add(Message.system(in.systemPrompt()));
        }
        messages.add(Message.user(in.userPrompt()));
        return new LlmRequest(in.model(), messages, in.maxTokens(), in.temperature());
    }

    public record Input(String systemPrompt, String userPrompt, String model,
                        Integer maxTokens, Double temperature) {
    }

    public record Output(String content, String provider, String model,
                         int promptTokens, int completionTokens, double costUsd) {
    }
}
