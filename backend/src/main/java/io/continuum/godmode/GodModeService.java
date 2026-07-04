package io.continuum.godmode;

import io.continuum.godmode.memory.MemoryEngine;
import io.continuum.persistence.entity.GodModeConfigEntity;
import io.continuum.persistence.repository.GodModeConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * God Mode control plane: per-developer opt-in state and the transparent hooks
 * other subsystems consult.
 *
 * The compatibility contract mirrors V4's PolicyResolver: {@link #configIfEnabled}
 * returns empty unless the developer explicitly enabled God Mode — and every
 * caller treats empty as "do exactly what you did before God Mode existed".
 */
@Service
public class GodModeService {

    private static final Logger log = LoggerFactory.getLogger(GodModeService.class);

    private final GodModeConfigRepository configs;
    private final MemoryEngine memory;

    public GodModeService(GodModeConfigRepository configs, MemoryEngine memory) {
        this.configs = configs;
        this.memory = memory;
    }

    // ---- the compatibility guarantee ----

    /** Empty unless this developer opted in — the single gate every hook checks. */
    public Optional<GodModeConfigEntity> configIfEnabled(String developerId) {
        if (developerId == null) {
            return Optional.empty();
        }
        return configs.findById(developerId).filter(GodModeConfigEntity::isEnabled);
    }

    // ---- toggle & profile ----

    @Transactional
    public GodModeConfigEntity getOrCreate(String developerId) {
        return configs.findById(developerId)
                .orElseGet(() -> configs.save(new GodModeConfigEntity(developerId)));
    }

    @Transactional
    public GodModeConfigEntity setEnabled(String developerId, boolean enabled) {
        GodModeConfigEntity c = getOrCreate(developerId);
        c.setEnabled(enabled);
        configs.save(c);
        log.info("God Mode {} for developer {}", enabled ? "ENABLED" : "DISABLED", developerId);
        return c;
    }

    @Transactional
    public GodModeConfigEntity updateSettings(String developerId, Boolean memact, Boolean twinGate,
                                              Integer contextBudgetTokens) {
        GodModeConfigEntity c = getOrCreate(developerId);
        if (memact != null) {
            c.setMemactEnabled(memact);
        }
        if (twinGate != null) {
            c.setTwinGateEnabled(twinGate);
        }
        if (contextBudgetTokens != null && contextBudgetTokens >= 1000) {
            c.setContextBudgetTokens(contextBudgetTokens);
        }
        return configs.save(c);
    }

    public List<GodModeConfigEntity> enabledConfigs() {
        return configs.findByEnabledTrue();
    }

    // ---- transparent gateway hook (never throws, no-op when off) ----

    /** Observe one gateway exchange into working memory. Zero effect when God Mode is off. */
    public void observeExchange(String developerId, String sessionId, String userContent, String assistantContent) {
        try {
            configIfEnabled(developerId).filter(GodModeConfigEntity::isMemactEnabled).ifPresent(c -> {
                memory.ingest(c, sessionId, "user", userContent);
                memory.ingest(c, sessionId, "assistant", assistantContent);
            });
        } catch (Exception e) {
            // Memory must never affect the request path.
            log.debug("god-mode observe skipped: {}", e.getMessage());
        }
    }

    // ---- status ----

    @Transactional(readOnly = true)
    public Map<String, Object> status(String developerId) {
        GodModeConfigEntity c = getOrCreate(developerId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", c.isEnabled());
        out.put("memactEnabled", c.isMemactEnabled());
        out.put("twinGateEnabled", c.isTwinGateEnabled());
        out.put("contextBudgetTokens", c.getContextBudgetTokens());
        out.put("ttls", Map.of("workingMinutes", c.getWorkingTtlMinutes(),
                "episodicDays", c.getEpisodicTtlDays(), "semanticDays", c.getSemanticTtlDays()));
        out.put("quotas", Map.of("working", c.getMaxWorkingItems(),
                "episodic", c.getMaxEpisodicItems(), "semanticNodes", c.getMaxSemanticNodes()));
        out.put("memory", c.isEnabled() ? memory.tierSnapshot(c) : Map.of());
        out.put("memAct", memory.memActState());
        return out;
    }
}
