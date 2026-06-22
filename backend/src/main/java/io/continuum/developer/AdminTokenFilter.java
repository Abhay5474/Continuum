package io.continuum.developer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.portal.PortalSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Protects admin/onboarding endpoints with a static {@code CONTINUUM_ADMIN_TOKEN}
 * presented via {@code X-Admin-Token}. If the token is unset (local dev), access
 * is allowed with a warning so onboarding is frictionless; in any real deployment
 * the token must be configured.
 */
public class AdminTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminTokenFilter.class);

    private final String adminToken;
    private final ObjectMapper mapper;
    private final PortalSessionService sessions;

    public AdminTokenFilter(String adminToken, ObjectMapper mapper, PortalSessionService sessions) {
        this.adminToken = adminToken == null ? "" : adminToken;
        this.mapper = mapper;
        this.sessions = sessions;
    }

    /** Allow a logged-in operator (OPERATOR session token) to use admin endpoints. */
    private boolean acceptsOperatorSession(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return false;
        }
        Optional<PortalSessionService.Session> s = sessions.verify(auth.substring(7).trim());
        return s.isPresent() && s.get().role() == PortalSessionService.Role.OPERATOR;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (acceptsOperatorSession(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (adminToken.isBlank()) {
            log.warn("CONTINUUM_ADMIN_TOKEN not set — admin endpoint {} is unprotected (dev mode)", request.getRequestURI());
            chain.doFilter(request, response);
            return;
        }
        String presented = request.getHeader("X-Admin-Token");
        if (presented == null || !constantTimeEquals(presented, adminToken)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getWriter(),
                    Map.of("error", "unauthorized", "message", "Valid X-Admin-Token required."));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
