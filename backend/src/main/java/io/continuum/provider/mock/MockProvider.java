package io.continuum.provider.mock;

import io.continuum.provider.LlmProvider;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import io.continuum.provider.model.Role;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Always-available, deterministic, zero-cost provider.
 *
 * It is the safety net at the end of the failover chain so the system is fully
 * demonstrable with no API keys, and it gives tests a stable LLM. Its output is
 * a function of the prompt, so replay assertions are easy to reason about.
 */
@Component
public class MockProvider implements LlmProvider {

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public double estimateCost(String model, int promptTokens, int completionTokens) {
        return 0.0;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String lastUser = request.messages().stream()
                .filter(m -> m.role() == Role.USER)
                .map(Message::content)
                .reduce((a, b) -> b)
                .orElse("");
        String content = "[mock-llm] " + summarize(lastUser);
        int promptTokens = request.messages().stream()
                .mapToInt(m -> m.content() == null ? 0 : m.content().length() / 4).sum();
        int completionTokens = content.length() / 4;
        return new LlmResponse(content, List.of(), promptTokens, completionTokens, name(), "mock-1", "stop");
    }

    private String summarize(String input) {
        String trimmed = input == null ? "" : input.strip();
        if (trimmed.isEmpty()) {
            return "No input provided.";
        }
        String oneLine = trimmed.replaceAll("\\s+", " ");
        String head = oneLine.length() > 160 ? oneLine.substring(0, 160) + "…" : oneLine;
        return "Analysis of: \"" + head + "\". Assessment: LOW RISK. Confidence: 0.82.";
    }
}
