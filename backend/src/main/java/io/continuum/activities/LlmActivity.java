package io.continuum.activities;

import io.continuum.chaos.ChaosMonkey;
import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import io.continuum.provider.ProviderRouter;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Invokes an LLM as a durable activity.
 *
 * The call goes through {@link ProviderRouter} so provider failover is automatic
 * and invisible to the workflow. The result is recorded as
 * {@code ACTIVITY_COMPLETED}, so on replay the model is NEVER called again — the
 * recorded answer is returned, preventing workflow divergence. Token usage and
 * estimated cost are buffered for atomic commit with the completion event.
 */
@Component
public class LlmActivity implements Activity {

    public static final String TYPE = "llm.complete";

    private final ProviderRouter router;
    private final ChaosMonkey chaos;

    public LlmActivity(ProviderRouter router, ChaosMonkey chaos) {
        this.router = router;
        this.chaos = chaos;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object execute(String inputJson, ActivityContext ctx) {
        chaos.maybeFailActivity(TYPE);

        Input in = ctx.input(inputJson, Input.class);
        List<Message> messages = new ArrayList<>();
        if (in.systemPrompt() != null && !in.systemPrompt().isBlank()) {
            messages.add(Message.system(in.systemPrompt()));
        }
        messages.add(Message.user(in.userPrompt()));

        LlmRequest request = new LlmRequest(in.model(), messages, in.maxTokens(), in.temperature());
        LlmResponse response = router.complete(request);

        double cost = router.estimateCost(response.provider(), response.model(),
                response.promptTokens(), response.completionTokens());
        ctx.recordCost(response.provider(), response.model(),
                response.promptTokens(), response.completionTokens(), cost);

        return new Output(response.content(), response.provider(), response.model(),
                response.promptTokens(), response.completionTokens(), cost);
    }

    public record Input(String systemPrompt, String userPrompt, String model,
                        Integer maxTokens, Double temperature) {
    }

    public record Output(String content, String provider, String model,
                         int promptTokens, int completionTokens, double costUsd) {
    }
}
