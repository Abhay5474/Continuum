package io.continuum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Continuum — a durable execution engine for AI agents.
 *
 * The engine treats every external dependency (LLM providers, email, payments)
 * as unreliable. Forward progress is guaranteed through event sourcing,
 * deterministic replay, a durable task queue and the transactional outbox.
 */
@SpringBootApplication
@EnableScheduling
public class ContinuumApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContinuumApplication.class, args);
    }
}
