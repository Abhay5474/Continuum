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
        config.setExposedHeaders(List.of("X-Continuum-Session", "X-Continuum-Request-Id", "X-Continuum-Applied", "Retry-After", "X-RateLimit-Remaining"));
        config.setMaxAge(3600L);

        // The OpenAI-shaped surface is called by OpenAI SDKs, which add headers
        // of their own (x-stainless-*, OpenAI-Organization); a browser app using
        // one failed its preflight. No cookies are involved, only the bearer
        // key, so any request header is safe to allow there.
        CorsConfiguration openAi = new CorsConfiguration(config);
        openAi.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/v1/**", openAi);

        FilterRegistrationBean<CorsFilter> reg = new FilterRegistrationBean<>(new CorsFilter(source));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    /** Operational endpoints for operators only; see {@link MetricsAccessFilter}. */
    @Bean
    public FilterRegistrationBean<MetricsAccessFilter> metricsAccessFilter(
            @org.springframework.beans.factory.annotation.Value("${CONTINUUM_METRICS_TOKEN:}") String token) {
        FilterRegistrationBean<MetricsAccessFilter> reg = new FilterRegistrationBean<>(new MetricsAccessFilter(token));
        reg.addUrlPatterns("/actuator/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return reg;
    }
}
