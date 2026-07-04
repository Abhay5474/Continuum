package io.continuum.godmode;

import io.continuum.godmode.memory.MemoryEngine;
import io.continuum.persistence.entity.GodModeConfigEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The autonomous consolidation heartbeat. Iterates ONLY developers who opted
 * in; when nobody has, each tick is a single indexed SELECT — zero work.
 */
@Component
@ConditionalOnProperty(value = "continuum.godmode.loop-enabled", havingValue = "true", matchIfMissing = true)
public class GodModeLoop {

    private static final Logger log = LoggerFactory.getLogger(GodModeLoop.class);

    private final GodModeService godMode;
    private final MemoryEngine memory;

    public GodModeLoop(GodModeService godMode, MemoryEngine memory) {
        this.godMode = godMode;
        this.memory = memory;
    }

    @Scheduled(fixedDelayString = "${continuum.godmode.loop-interval-ms:60000}",
            initialDelayString = "${continuum.godmode.loop-interval-ms:60000}")
    public void tick() {
        for (GodModeConfigEntity config : godMode.enabledConfigs()) {
            try {
                memory.consolidate(config);
            } catch (Exception e) {
                log.warn("god-mode consolidation failed for {}: {}",
                        config.getDeveloperId(), e.getMessage());
            }
        }
    }
}
