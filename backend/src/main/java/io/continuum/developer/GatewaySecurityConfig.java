package io.continuum.developer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the API-key filter for gateway routes ONLY. By scoping the URL
 * patterns here, no authentication is imposed on any pre-existing endpoint —
 * full backward compatibility.
 */
@Configuration
public class GatewaySecurityConfig {

    @Bean
    public FilterRegistrationBean<ApiKeyAuthenticationFilter> gatewayAuthFilter(
            DeveloperService developerService, ObjectMapper mapper) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new ApiKeyAuthenticationFilter(developerService, mapper));
        // Only the developer chat endpoints require API-key auth; operator
        // endpoints (/api/gateway/stats, /requests, /health) stay open for the dashboard.
        reg.addUrlPatterns("/api/gateway/chat", "/v1/chat/completions");
        reg.setOrder(1);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<AdminTokenFilter> adminTokenFilter(
            @Value("${CONTINUUM_ADMIN_TOKEN:}") String adminToken, ObjectMapper mapper) {
        FilterRegistrationBean<AdminTokenFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new AdminTokenFilter(adminToken, mapper));
        reg.addUrlPatterns("/api/admin/*");
        reg.setOrder(1);
        return reg;
    }
}
