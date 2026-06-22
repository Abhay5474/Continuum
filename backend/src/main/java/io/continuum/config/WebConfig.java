package io.continuum.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
@EnableConfigurationProperties({EngineProperties.class, LlmProperties.class})
public class WebConfig {

    /**
     * CORS handled by a dedicated filter at the HIGHEST precedence — ahead of the
     * gateway/admin/portal auth filters.
     *
     * This matters for correctness: a browser request that fails authentication
     * still needs the {@code Access-Control-Allow-Origin} header so the client can
     * actually read the {@code 401} (otherwise it surfaces as an opaque "CORS
     * error"). Running CORS first also answers preflight {@code OPTIONS} requests
     * before any auth check. Covers both the management API and the OpenAI-shaped
     * {@code /v1/**} gateway alias; the {@code Authorization} header is allowed.
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Admin-Token", "X-Continuum-Session"));
        config.setExposedHeaders(List.of("X-Continuum-Session"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/v1/**", config);

        FilterRegistrationBean<CorsFilter> reg = new FilterRegistrationBean<>(new CorsFilter(source));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
}
