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

    /**
     * Injection rates per tenant, plus an engine-wide profile under
     * {@link #GLOBAL} that only the operator can arm.
     *
     * <p>These were global. On a shared engine that meant one developer running a
     * hallucination drill corrupted every other tenant's model responses, which
     * makes the feature unusable in production — the opposite of its purpose.
     */
    private static final String GLOBAL = "\u0000global";
    private final Map<String, Map<AiFailureType, Double>> rates = new ConcurrentHashMap<>();

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

    /**
     * Whether anything is armed for the work this thread is doing — the current
     * tenant's own drill, or the operator's engine-wide one.
     */
    public boolean isActive() {
        String dev = io.continuum.portal.TenantContext.developerId();
        return isActive(null) || (dev != null && isActive(dev));
    }

    /** Whether anything is armed for the given scope (null = engine-wide). */
    public boolean isActive(String developerId) {
        return profile(developerId).values().stream().anyMatch(r -> r > 0);
    }

    public void setRate(String developerId, AiFailureType type, double rate) {
        profile(developerId).put(type, Math.max(0, Math.min(1, rate)));
    }

    public void reset(String developerId) {
        profile(developerId).clear();
    }

    private Map<AiFailureType, Double> profile(String developerId) {
        return rates.computeIfAbsent(developerId == null ? GLOBAL : developerId,
                k -> new ConcurrentHashMap<>());
    }

    public Map<String, Double> state(String developerId) {
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        for (AiFailureType t : AiFailureType.values()) {
            out.put(t.name(), profile(developerId).getOrDefault(t, 0.0));
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

    /**
     * Fires if either the current tenant's profile or the operator's global one
     * is armed for this failure type.
     */
    private boolean fire(AiFailureType type) {
        String dev = io.continuum.portal.TenantContext.developerId();
        double rate = profile(GLOBAL).getOrDefault(type, 0.0);
        if (dev != null) {
            Map<AiFailureType, Double> mine = rates.get(dev);
            if (mine != null) {
                rate = Math.max(rate, mine.getOrDefault(type, 0.0));
            }
        }
        return rate > 0 && ThreadLocalRandom.current().nextDouble() < rate;
    }

    private void record(String workflowId, AiFailureType type, Long seq, String detail) {
        log.warn("AI-CHAOS: injected {} into workflow {} (seq {})", type, workflowId, seq);
        try {
            AiChaosEventEntity row = new AiChaosEventEntity(workflowId, type.name(), seq, detail);
            row.setDeveloperId(io.continuum.portal.TenantContext.developerId());
            eventRepo.save(row);
        } catch (Exception e) {
            log.debug("could not persist ai-chaos event: {}", e.getMessage());
        }
    }
}
