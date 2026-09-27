package io.continuum.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on every {@code @Scheduled} poller, sweeper and tick in the process.
 *
 * <p>On unless {@code continuum.scheduling.enabled=false}. The integration
 * tests switch it off and drive the queue pollers by hand, so each step of a
 * crash-and-recover scenario happens exactly when the test says it does.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "continuum.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
