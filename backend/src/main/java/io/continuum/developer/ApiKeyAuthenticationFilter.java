package io.continuum.developer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.persistence.entity.DeveloperEntity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates Continuum API keys on gateway routes only.
 *
 * Registered exclusively for {@code /api/gateway/*} and {@code /v1/*} (see
 * {@link GatewaySecurityConfig}), so every existing Continuum endpoint stays
 * exactly as it was — no auth added to V1/V2 APIs. On success the authenticated
 * {@link DeveloperEntity} is attached as a request attribute; on failure it
 * returns a generic 401 (no information leakage about why).
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String DEVELOPER_ATTRIBUTE = "continuum.developer";

    private final DeveloperService developerService;
    private final ObjectMapper mapper;

    public ApiKeyAuthenticationFilter(DeveloperService developerService, ObjectMapper mapper) {
        this.developerService = developerService;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7).trim() : null;

        Optional<DeveloperEntity> developer = token == null ? Optional.empty() : safeAuthenticate(token);
        if (developer.isEmpty()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getWriter(),
                    Map.of("error", "invalid_api_key",
                            "message", "Missing or invalid Continuum API key. Use 'Authorization: Bearer cnt_live_...'."));
            return;
        }
        request.setAttribute(DEVELOPER_ATTRIBUTE, developer.get());
        chain.doFilter(request, response);
    }

    private Optional<DeveloperEntity> safeAuthenticate(String token) {
        try {
            return developerService.authenticate(token);
        } catch (Exception e) {
            // Never leak internal errors as auth signal.
            return Optional.empty();
        }
    }
}
