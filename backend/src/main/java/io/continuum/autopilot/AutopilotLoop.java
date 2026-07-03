package io.continuum.autopilot;

import io.continuum.persistence.entity.AutopilotConfigEntity;
import io.continuum.persistence.repository.AutopilotConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the Autopilot closed loop on a schedule, but ONLY for developers who
 * have explicitly enabled it. Developers with Autopilot off incur zero loop work.
 */
@Component
@ConditionalOnProperty(prefix = "continuum.autopilot", name = "loop-enabled", havingValue = "true", matchIfMissing = true)
public class AutopilotLoop {

    private static final Logger log = LoggerFactory.getLogger(AutopilotLoop.class);

    private final AutopilotConfigRepository configRepo;
    private final AutopilotService autopilot;

    public AutopilotLoop(AutopilotConfigRepository configRepo, AutopilotService autopilot) {
        this.configRepo = configRepo;
        this.autopilot = autopilot;
    }

    @Scheduled(fixedDelayString = "${continuum.autopilot.loop-interval-ms:30000}", initialDelay = 15000)
    public void tick() {
        for (AutopilotConfigEntity config : configRepo.findByEnabledTrue()) {
            try {
                autopilot.runLoopFor(config.getDeveloperId());
            } catch (Exception e) {
                log.warn("Autopilot loop failed for {}: {}", config.getDeveloperId(), e.getMessage());
            }
        }
    }
}
