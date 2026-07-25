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
 * Protects admin endpoints with a static {@code CONTINUUM_ADMIN_TOKEN} presented
 * via {@code X-Admin-Token}, or an {@code OPERATOR} session.
 *
 * <p>This filter used to fail <em>open</em>: an unset token meant every admin
 * endpoint was anonymous, so anyone could list all accounts, mint developers and
 * issue API keys. It now fails <em>closed</em> — with no token configured the admin
 * surface is simply unavailable. Nothing legitimate is lost: developers onboard
 * through {@code /api/portal/developer/signup}, which is the supported public path.
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
            // Fail closed. An unconfigured admin token must never mean "anyone".
            log.warn("CONTINUUM_ADMIN_TOKEN not set — admin endpoint {} refused", request.getRequestURI());
            deny(response, "Admin access is not configured on this deployment.");
            return;
        }
        String presented = request.getHeader("X-Admin-Token");
        if (presented == null || !constantTimeEquals(presented, adminToken)) {
            deny(response, "Valid X-Admin-Token required.");
            return;
        }
        chain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(), Map.of("error", "unauthorized", "message", message));
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
