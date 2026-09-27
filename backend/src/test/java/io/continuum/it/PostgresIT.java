package io.continuum.it;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Base for integration tests: the whole application, on a real PostgreSQL.
 *
 * <p>The unit tests use fakes, so until these existed nothing automated ever
 * ran the queue's {@code FOR UPDATE SKIP LOCKED}, the Flyway migrations, the JPA
 * mappings or the auth filters' URL coverage. These do.
 *
 * <p>The database is {@code CONTINUUM_IT_DB_URL} (default: a local
 * {@code continuum_it}), with {@code CONTINUUM_IT_DB_USER} /
 * {@code CONTINUUM_IT_DB_PASSWORD}. Without one reachable the tests are skipped —
 * unless {@code CONTINUUM_IT_REQUIRED=true}, as in CI, where a missing database
 * must fail the build rather than quietly skip it.
 *
 * <p>Scheduling is off: tests call the pollers and the sweeper themselves, so
 * every step of a crash-and-recover scenario happens exactly when they say.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "continuum.scheduling.enabled=false",
        // Every entity is checked against the schema the migrations built.
        "spring.jpa.hibernate.ddl-auto=validate",
        "CONTINUUM_SESSION_KEY=integration-test-session-key-0123456789",
        "GEMINI_API_KEY=", "GROQ_API_KEY=",
        "logging.level.io.continuum=WARN"
})
@ExtendWith(PostgresIT.RequiresPostgres.class)
public abstract class PostgresIT {

    static final String URL = env("CONTINUUM_IT_DB_URL", "jdbc:postgresql://localhost:5432/continuum_it");
    static final String USER = env("CONTINUUM_IT_DB_USER", "continuum");
    static final String PASSWORD = env("CONTINUUM_IT_DB_PASSWORD", "abhay123");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> URL);
        r.add("spring.datasource.username", () -> USER);
        r.add("spring.datasource.password", () -> PASSWORD);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** Skips the class when no database answers, unless the build requires one. */
    public static final class RequiresPostgres implements ExecutionCondition {
        private static Boolean reachable;

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if ("true".equalsIgnoreCase(System.getenv("CONTINUUM_IT_REQUIRED"))) {
                return ConditionEvaluationResult.enabled("database required");
            }
            if (reachable == null) {
                DriverManager.setLoginTimeout(3);
                try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
                    reachable = true;
                } catch (Exception e) {
                    reachable = false;
                }
            }
            return reachable ? ConditionEvaluationResult.enabled("database reachable")
                    : ConditionEvaluationResult.disabled("No PostgreSQL at " + URL + " (set CONTINUUM_IT_DB_URL)");
        }
    }
}
