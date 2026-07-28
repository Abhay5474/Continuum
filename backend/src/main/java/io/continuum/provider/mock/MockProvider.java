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

    /**
     * Artificial service time, in milliseconds.
     *
     * <p>Zero by default, so nothing changes for tests or ordinary use. It
     * exists because the mock answers in about three milliseconds, and at that
     * timescale every latency-based control in Continuum — admission control's
     * congestion gradient especially — is measuring thread scheduling rather
     * than a provider. A hosted model sits between 200ms and several seconds,
     * and this is the only way to exercise those controls against something
     * that behaves like one.
     *
     * <p>Set with {@code CONTINUUM_MOCK_LATENCY_MS}.
     */
    private final long serviceTimeMs = Long.parseLong(
            System.getenv().getOrDefault("CONTINUUM_MOCK_LATENCY_MS", "0"));

    /**
     * How many calls this provider will genuinely serve at once.
     *
     * <p>Unbounded by default. A sleep-based mock has no capacity ceiling — a
     * hundred concurrent 250ms sleeps finish in 250ms — which makes it useless
     * for exercising anything that defends against a saturated provider. Real
     * providers have a concurrency quota, and past it requests queue and latency
     * climbs. This reproduces that, so admission control has a real bottleneck
     * to protect against rather than an imaginary one.
     *
     * <p>Set with {@code CONTINUUM_MOCK_CONCURRENCY}.
     */
    private final int capacity = Integer.parseInt(
            System.getenv().getOrDefault("CONTINUUM_MOCK_CONCURRENCY", "0"));

    private final java.util.concurrent.Semaphore permits =
            new java.util.concurrent.Semaphore(capacity > 0 ? capacity : Integer.MAX_VALUE, true);

    /** Simulated service time, applied per call. Interruption is not swallowed. */
    private void serve() {
        if (serviceTimeMs <= 0 && capacity <= 0) {
            return;
        }
        boolean held = false;
        try {
            if (capacity > 0) {
                permits.acquire();
                held = true;
            }
            if (serviceTimeMs > 0) {
                Thread.sleep(serviceTimeMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (held) {
                permits.release();
            }
        }
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    /**
     * Priced per model so a keyless deployment still has a real cost gradient to
     * route and cascade over. A single free model made every cost comparison
     * degenerate.
     */
    @Override
    public double estimateCost(String model, int promptTokens, int completionTokens) {
        boolean large = model != null && model.contains("large");
        double in = large ? 0.0005 : 0.00002;
        double out = large ? 0.0015 : 0.00004;
        return (promptTokens / 1000.0) * in + (completionTokens / 1000.0) * out;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String lastUser = request.messages().stream()
                .filter(m -> m.role() == Role.USER)
                .map(Message::content)
                .reduce((a, b) -> b)
                .orElse("");
        // The small model answers briefly and the large one at length — the
        // difference small models actually exhibit, and enough for a cascade to
        // have something real to judge.
        serve();
        String model = request.model() == null ? "mock-small" : request.model();
        boolean large = model.contains("large");
        String content = "[mock-llm] " + (large ? elaborate(lastUser) : summarize(lastUser));
        int promptTokens = request.messages().stream()
                .mapToInt(m -> m.content() == null ? 0 : m.content().length() / 4).sum();
        int completionTokens = content.length() / 4;
        return new LlmResponse(content, List.of(), promptTokens, completionTokens, name(), model, "stop");
    }

    /** The large model's answer: the same assessment, worked through. */
    private String elaborate(String input) {
        String trimmed = input == null ? "" : input.strip();
        if (trimmed.isEmpty()) {
            return "No input provided.";
        }
        String oneLine = trimmed.replaceAll("\\s+", " ");
        String head = oneLine.length() > 160 ? oneLine.substring(0, 160) + "\u2026" : oneLine;
        return "Analysis of: \"" + head + "\". Assessment: LOW RISK. Confidence: 0.82. "
                + "Reasoning: the request was decomposed into its constituent claims and each was "
                + "checked against the supplied context. No contradictions were found between the "
                + "stated requirements and the available evidence, and no obligation appears to "
                + "survive the stated term. Recommended next step: confirm the counterparty's "
                + "position before relying on this assessment.";
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
