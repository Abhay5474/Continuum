package io.continuum.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Many managed Postgres providers (Render, Heroku, Neon, Railway) expose a
 * single {@code DATABASE_URL} of the form
 * {@code postgres://user:pass@host:port/db}. Spring needs a JDBC URL plus
 * separate credentials, so we translate it here before the context starts.
 *
 * If {@code DATABASE_URL} is absent the configured {@code spring.datasource.*}
 * values (e.g. local Postgres) are used unchanged.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String databaseUrl = environment.getProperty("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()
                || !(databaseUrl.startsWith("postgres://") || databaseUrl.startsWith("postgresql://"))) {
            return;
        }
        try {
            URI uri = URI.create(databaseUrl);
            String[] userInfo = uri.getUserInfo() != null ? uri.getUserInfo().split(":", 2) : new String[]{"", ""};
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String jdbc = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath()
                    + (uri.getQuery() != null ? "?" + uri.getQuery() : "");

            Map<String, Object> props = new HashMap<>();
            props.put("spring.datasource.url", jdbc);
            props.put("spring.datasource.username", userInfo[0]);
            props.put("spring.datasource.password", userInfo.length > 1 ? userInfo[1] : "");
            environment.getPropertySources().addFirst(new MapPropertySource("continuum-database-url", props));
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse DATABASE_URL", e);
        }
    }
}
