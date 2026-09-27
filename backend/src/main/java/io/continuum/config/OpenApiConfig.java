package io.continuum.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.NumberSchema;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

/**
 * The API description the console's types are generated from.
 *
 * <p>It has to describe what is actually sent. Jackson writes an {@link Instant}
 * as epoch seconds — a number such as {@code 1790491371.011} — not the ISO string
 * springdoc assumes, so the schema says so; a generated type of {@code string}
 * would be a lie the compiler then enforces.
 */
@Configuration
public class OpenApiConfig {

    static {
        SpringDocUtils.getConfig().replaceWithSchema(Instant.class,
                new NumberSchema().description("Epoch seconds, possibly fractional"));
    }

    @Bean
    public OpenAPI continuumOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Continuum")
                .description("Durable execution engine for AI agents: workflows, gateway and console APIs.")
                .version("1"));
    }
}
