package io.continuum.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every migration applies to a real PostgreSQL, and every entity matches the
 * schema they produce ({@code ddl-auto=validate} fails the context otherwise).
 */
class MigrationsIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;

    @Test
    void everyMigrationIsAppliedAndNoneFailed() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql");
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL", Integer.class);
        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class);

        assertThat(failed).isZero();
        assertThat(applied).isEqualTo(files.length);
    }
}
