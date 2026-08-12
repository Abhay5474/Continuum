package io.continuum.provider.mock;

import io.continuum.chaos.ChaosMonkey;
import io.continuum.provider.LlmProvider;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import io.continuum.provider.model.ResponseFormat;
import io.continuum.provider.model.Role;
import io.continuum.provider.model.ToolCall;
import io.continuum.provider.model.ToolSpec;
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
     * Optional so the provider stays constructible in a plain unit test, where
     * there is no Spring context and no chaos to consult.
     */
    private final ChaosMonkey chaos;

    public MockProvider() {
        this(null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MockProvider(ChaosMonkey chaos) {
        this.chaos = chaos;
    }

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

    /**
     * Whether the safety-net provider is up.
     *
     * <p>Always, unless {@code CONTINUUM_MOCK_UNAVAILABLE} says otherwise. The
     * mock is the last entry in every fallback chain, which means the chain can
     * never actually be exhausted while it answers — so nothing that handles
     * total provider failure can be exercised end to end without a way to take
     * it down.
     */
    private final boolean unavailable = Boolean.parseBoolean(
            System.getenv().getOrDefault("CONTINUUM_MOCK_UNAVAILABLE", "false"));

    @Override
    public boolean isAvailable() {
        return !unavailable;
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
        if (unavailable) {
            throw new IllegalStateException("mock provider is marked unavailable");
        }
        // A real provider fails intermittently, and a mock that never does makes
        // failover untestable — which is the one behaviour this product is most
        // often judged on. Armed through the chaos API, off unless asked for.
        if (chaos != null && chaos.shouldFailProviderCall()) {
            throw new ChaosMonkey.SimulatedProviderFailure(
                    "mock provider: injected failure (chaos)");
        }
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

        int promptTokens = request.messages().stream()
                .mapToInt(m -> m.content() == null ? 0 : m.content().length() / 4).sum();

        // ---- tool calling -------------------------------------------------
        // A provider that accepts `tools` and never calls one leaves the whole
        // tool path untested: the request plumbing looks fine and the response
        // plumbing has never once carried a call. So the mock answers a tool
        // offer the way a real model does — with a call rather than prose —
        // unless the caller explicitly said not to.
        if (request.hasTools() && !"none".equalsIgnoreCase(String.valueOf(request.toolChoice()))) {
            ToolSpec picked = pickTool(request);
            if (picked != null) {
                ToolCall call = new ToolCall("call_mock_1", picked.name(), argumentsFor(picked, lastUser));
                return new LlmResponse(null, List.of(call), promptTokens, 6, name(), model, "tool_calls");
            }
        }

        // ---- images -------------------------------------------------------
        // Named in the answer so a caller can tell the parts actually arrived
        // rather than being silently dropped on the way through.
        long images = request.messages().stream()
                .filter(Message::hasImages)
                .mapToLong(m -> m.images().size())
                .sum();

        String body = large ? elaborate(lastUser) : summarize(lastUser);
        if (images > 0) {
            body = body + " Received " + images + (images == 1 ? " image." : " images.");
        }

        // ---- response_format ----------------------------------------------
        // A caller who asked for a JSON object and got prose has been lied to
        // by the gateway, not by the model.
        String content;
        if (request.responseFormat() != null && request.responseFormat().isJson()) {
            content = "{\"assessment\":\"LOW RISK\",\"confidence\":0.82,\"echo\":"
                    + quote(lastUser) + "}";
        } else {
            content = "[mock-llm] " + body;
        }

        int completionTokens = content.length() / 4;
        return new LlmResponse(content, List.of(), promptTokens, completionTokens, name(), model, "stop");
    }

    /** The named function when tool_choice names one, otherwise the first offered. */
    private ToolSpec pickTool(LlmRequest request) {
        String choice = request.toolChoice();
        if (choice != null && !"auto".equalsIgnoreCase(choice) && !"required".equalsIgnoreCase(choice)) {
            for (ToolSpec t : request.tools()) {
                if (t.name().equals(choice)) {
                    return t;
                }
            }
        }
        return request.tools().isEmpty() ? null : request.tools().get(0);
    }

    /**
     * Arguments that satisfy the tool's own schema where it declares string
     * properties, so a caller parsing them against their schema gets something
     * valid rather than a fixed stub that fails their validator.
     */
    @SuppressWarnings("unchecked")
    private String argumentsFor(ToolSpec tool, String userText) {
        StringBuilder json = new StringBuilder("{");
        Object props = tool.parameters() == null ? null : tool.parameters().get("properties");
        if (props instanceof java.util.Map<?, ?> map) {
            boolean first = true;
            for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                String type = "string";
                if (e.getValue() instanceof java.util.Map<?, ?> spec && spec.get("type") != null) {
                    type = String.valueOf(spec.get("type"));
                }
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append(quote(key)).append(':');
                switch (type) {
                    case "number", "integer" -> json.append('1');
                    case "boolean" -> json.append("true");
                    case "array" -> json.append("[]");
                    case "object" -> json.append("{}");
                    default -> json.append(quote(userText == null ? "" : userText.strip()));
                }
            }
        }
        return json.append('}').toString();
    }

    private static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ") + '"';
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
