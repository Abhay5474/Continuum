package io.continuum.aichaos;

import io.continuum.aichaos.injectors.*;
import io.continuum.persistence.entity.AiChaosEventEntity;
import io.continuum.persistence.repository.AiChaosEventRepository;
import io.continuum.provider.model.LlmRequest;
import io.continuum.provider.model.LlmResponse;
import io.continuum.provider.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Extension 3 — the AI Failure Injection Framework ("Chaos Monkey for AI").
 *
 * Holds per-failure-type injection probabilities (all 0 by default, so V1
 * behavior is untouched) and applies the relevant injectors around the real LLM
 * call. Every injection is recorded so survival/recovery metrics are measured
 * from real outcomes, not simulated numbers.
 */
@Service
public class AiChaosEngine {

    private static final Logger log = LoggerFactory.getLogger(AiChaosEngine.class);

    private final Map<AiFailureType, Double> rates = new ConcurrentHashMap<>();

    private final HallucinationInjector hallucination;
    private final SchemaCorruptionInjector schema;
    private final ToolCorruptionInjector tool;
    private final PromptInjectionSimulator promptInjection;
    private final ContextCorruptionSimulator context;
    private final ProviderDriftSimulator drift;
    private final AiChaosEventRepository eventRepo;

    public AiChaosEngine(HallucinationInjector hallucination, SchemaCorruptionInjector schema,
                         ToolCorruptionInjector tool, PromptInjectionSimulator promptInjection,
                         ContextCorruptionSimulator context, ProviderDriftSimulator drift,
                         AiChaosEventRepository eventRepo) {
        this.hallucination = hallucination;
        this.schema = schema;
        this.tool = tool;
        this.promptInjection = promptInjection;
        this.context = context;
        this.drift = drift;
        this.eventRepo = eventRepo;
    }

    public boolean isActive() {
        return rates.values().stream().anyMatch(r -> r > 0);
    }

    public void setRate(AiFailureType type, double rate) {
        rates.put(type, Math.max(0, Math.min(1, rate)));
    }

    public void reset() {
        rates.clear();
    }

    public Map<String, Double> state() {
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        for (AiFailureType t : AiFailureType.values()) {
            out.put(t.name(), rates.getOrDefault(t, 0.0));
        }
        return out;
    }

    /** Applied before the LLM call: prompt injection and context truncation. */
    public LlmRequest applyToRequest(LlmRequest request, String workflowId, Long seq) {
        LlmRequest result = request;
        if (fire(AiFailureType.PROMPT_INJECTION)) {
            List<Message> injected = promptInjection.inject(result.messages());
            result = new LlmRequest(result.model(), injected, result.maxTokens(), result.temperature());
            record(workflowId, AiFailureType.PROMPT_INJECTION, seq, "malicious instruction appended to context");
        }
        if (fire(AiFailureType.CONTEXT_TRUNCATION)) {
            List<Message> truncated = context.truncate(result.messages());
            result = new LlmRequest(result.model(), truncated, result.maxTokens(), result.temperature());
            record(workflowId, AiFailureType.CONTEXT_TRUNCATION, seq, "middle context removed");
        }
        return result;
    }

    /** Applied after the LLM call: hallucination, drift, schema and tool corruption. */
    public LlmResponse applyToResponse(LlmResponse response, String workflowId, Long seq) {
        String content = response.content();
        var tools = response.toolCalls();

        if (fire(AiFailureType.HALLUCINATION)) {
            content = hallucination.corrupt(content);
            record(workflowId, AiFailureType.HALLUCINATION, seq, "output replaced with reversed-decision text");
        }
        if (fire(AiFailureType.PROVIDER_DRIFT)) {
            content = drift.drift(content);
            record(workflowId, AiFailureType.PROVIDER_DRIFT, seq, "output reworded to simulate model revision");
        }
        if (fire(AiFailureType.SCHEMA_CORRUPTION)) {
            content = schema.corrupt(content);
            record(workflowId, AiFailureType.SCHEMA_CORRUPTION, seq, "structured output corrupted");
        }
        if (fire(AiFailureType.TOOL_CORRUPTION)) {
            tools = tool.corrupt(tools);
            record(workflowId, AiFailureType.TOOL_CORRUPTION, seq, "tool selection corrupted");
        }
        return new LlmResponse(content, tools, response.promptTokens(), response.completionTokens(),
                response.provider(), response.model(), response.finishReason());
    }

    /** Applied by the memory system when retrieving memories. */
    public String maybeCorruptMemory(String memory, String workflowId) {
        if (fire(AiFailureType.MEMORY_CORRUPTION)) {
            record(workflowId, AiFailureType.MEMORY_CORRUPTION, null, "retrieved memory corrupted");
            return context.corruptMemory(memory);
        }
        return memory;
    }

    private boolean fire(AiFailureType type) {
        double rate = rates.getOrDefault(type, 0.0);
        return rate > 0 && ThreadLocalRandom.current().nextDouble() < rate;
    }

    private void record(String workflowId, AiFailureType type, Long seq, String detail) {
        log.warn("AI-CHAOS: injected {} into workflow {} (seq {})", type, workflowId, seq);
        try {
            eventRepo.save(new AiChaosEventEntity(workflowId, type.name(), seq, detail));
        } catch (Exception e) {
            log.debug("could not persist ai-chaos event: {}", e.getMessage());
        }
    }
}
