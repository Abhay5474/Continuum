package io.continuum.registry;

import io.continuum.registry.catalog.ModelCatalogService;
import io.continuum.registry.catalog.ModelResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * When the model catalogue is refreshed.
 *
 * <p>At startup: the database only — the built-in mock models, and seeds for any
 * provider whose list has never been read. No provider is called on the startup
 * path, so a restart loop cannot turn into a stream of API requests.
 *
 * <p>Then an hourly tick that reads the database and asks: is a provider due?
 * A provider is due ten days after its last successful check (six hours after a
 * failed one). Measuring from the last success, rather than firing on fixed
 * dates, means a deployment that was off on a check day catches up the next
 * time it runs instead of waiting for the next date — and one that restarts
 * often still checks no more than about three times a month.
 *
 * <p>This replaced a monthly cron that re-ran discovery whose live read never
 * worked (it sent the model-list GET as a POST).
 */
@Component
public class ModelDiscoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ModelDiscoveryScheduler.class);

    private final ModelRegistryService registry;
    private final ModelCatalogService catalogue;
    private final ModelResolver resolver;

    public ModelDiscoveryScheduler(ModelRegistryService registry, ModelCatalogService catalogue, ModelResolver resolver) {
        this.registry = registry;
        this.catalogue = catalogue;
        this.resolver = resolver;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        try {
            int changes = registry.discoverAll();
            catalogue.applySeeds();
            log.info("Model registry seeded on startup ({} changes)", changes);
        } catch (Exception e) {
            log.warn("Startup model seeding failed: {}", e.getMessage());
        } finally {
            resolver.refresh();
        }
    }

    @Scheduled(fixedDelayString = "${continuum.models.tick-ms:3600000}",
            initialDelayString = "${continuum.models.first-tick-ms:120000}")
    public void tick() {
        try {
            catalogue.runIfDue();
        } catch (Exception e) {
            log.warn("Model catalogue tick failed: {}", e.getMessage());
        }
    }
}
