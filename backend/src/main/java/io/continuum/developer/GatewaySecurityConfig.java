package io.continuum.developer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.portal.ConsoleAuthFilter;
import io.continuum.portal.PortalAuthFilter;
import io.continuum.portal.PortalSessionService;
import io.continuum.portal.RateLimitFilter;
import io.continuum.portal.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers authentication filters for gateway, admin and portal routes ONLY. By
 * scoping the URL patterns here, no authentication is imposed on any pre-existing
 * endpoint — full backward compatibility. Every filter lets CORS preflight
 * (OPTIONS) pass through untouched.
 */
@Configuration
public class GatewaySecurityConfig {

    /**
     * Rate limiting, ordered ahead of every authentication filter.
     *
     * <p>It has to run first to be worth anything: the expensive work on the login
     * path is verifying the password, so a limiter that only sees authenticated
     * requests would never see the attack it exists to stop.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            RateLimiter limiter, PortalSessionService sessions, ObjectMapper mapper) {
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new RateLimitFilter(limiter, sessions, mapper));
        reg.addUrlPatterns("/api/*", "/v1/*");
        reg.setOrder(0);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthenticationFilter> gatewayAuthFilter(
            DeveloperService developerService, ObjectMapper mapper) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new ApiKeyAuthenticationFilter(developerService, mapper));
        // The developer chat endpoints authenticate with an API key; the console
        // read APIs authenticate with a session (see consoleAuthFilter below).
        // Pipelines are the same kind of endpoint — called by a program, not a
        // person — so they belong here rather than behind the console filter.
        reg.addUrlPatterns("/api/gateway/chat", "/v1/chat/completions",
                "/api/gateway/pipeline/*");
        reg.setOrder(1);
        return reg;
    }

    /**
     * Console/data APIs. These were previously anonymous, which exposed every
     * tenant's traces, stats and memory to any visitor. They now require a signed-in
     * developer (or the engine operator) and are tenant-scoped in the controllers.
     *
     * <p>Deliberately NOT covered: {@code /api/meta} and {@code /api/health}, which
     * carry only build/liveness info and are used by the public landing page.
     */
    @Bean
    public FilterRegistrationBean<ConsoleAuthFilter> consoleAuthFilter(
            PortalSessionService sessions, ObjectMapper mapper) {
        FilterRegistrationBean<ConsoleAuthFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new ConsoleAuthFilter(sessions, mapper));
        reg.addUrlPatterns(
                "/api/workflows/*", "/api/workflows",
                "/api/stats", "/api/costs",
                "/api/gateway/stats", "/api/gateway/requests", "/api/gateway/health",
                "/api/gateway/healing/*",
                "/api/dag/*", "/api/mmu/*", "/api/memory/*",
                "/api/replay/*", "/api/routing/*", "/api/hedging/*",
                "/api/chaos/*", "/api/chaos", "/api/ai-chaos/*", "/api/ai-chaos",
                "/api/models/*", "/api/models");
        reg.setOrder(1);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<AdminTokenFilter> adminTokenFilter(
            @Value("${CONTINUUM_ADMIN_TOKEN:}") String adminToken, ObjectMapper mapper,
            PortalSessionService sessions) {
        FilterRegistrationBean<AdminTokenFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new AdminTokenFilter(adminToken, mapper, sessions));
        reg.addUrlPatterns("/api/admin/*");
        reg.setOrder(1);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<PortalAuthFilter> portalAuthFilter(
            PortalSessionService sessions, ObjectMapper mapper) {
        FilterRegistrationBean<PortalAuthFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new PortalAuthFilter(sessions, mapper));
        reg.addUrlPatterns("/api/portal/developer/*");
        reg.setOrder(1);
        return reg;
    }
}
