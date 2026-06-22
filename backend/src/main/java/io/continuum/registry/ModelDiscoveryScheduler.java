package io.continuum.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Seeds the model registry at startup and re-runs discovery on a schedule
 * (monthly by default). Discovery is best-effort and tolerates offline providers.
 */
@Component
public class ModelDiscoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ModelDiscoveryScheduler.class);

    private final ModelRegistryService registry;

    public ModelDiscoveryScheduler(ModelRegistryService registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        try {
            int changes = registry.discoverAll();
            log.info("Model registry seeded on startup ({} changes)", changes);
        } catch (Exception e) {
            log.warn("Startup model discovery failed: {}", e.getMessage());
        }
    }

    // 03:00 on the 1st of every month.
    @Scheduled(cron = "${continuum.registry.discovery-cron:0 0 3 1 * *}")
    public void scheduledDiscovery() {
        try {
            int changes = registry.discoverAll();
            log.info("Scheduled model discovery complete ({} changes)", changes);
        } catch (Exception e) {
            log.warn("Scheduled model discovery failed: {}", e.getMessage());
        }
    }
}
