package io.continuum.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Authenticates developer-portal session tokens for {@code /api/portal/developer/**}
 * (excluding the open {@code /login} and {@code /signup} routes). The resolved
 * developer id is attached as a request attribute and scopes every portal action
 * to that developer — the foundation of multi-tenant isolation.
 */
public class PortalAuthFilter extends OncePerRequestFilter {

    public static final String DEVELOPER_ID_ATTRIBUTE = "continuum.portal.developerId";

    private final PortalSessionService sessions;
    private final ObjectMapper mapper;

    public PortalAuthFilter(PortalSessionService sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        // Open routes: anyone can sign up or log in — and an invitee has no
        // session yet, so accepting an invite cannot require one.
        if (path.endsWith("/login") || path.endsWith("/signup")
                || path.contains("/invites/accept") || path.contains("/invites/preview")) {
            chain.doFilter(request, response);
            return;
        }
        String token = bearer(request);
        Optional<PortalSessionService.Session> session = token == null ? Optional.empty() : sessions.verify(token);
        if (session.isEmpty() || session.get().role() != PortalSessionService.Role.DEVELOPER) {
            unauthorized(response);
            return;
        }
        request.setAttribute(DEVELOPER_ID_ATTRIBUTE, session.get().subject());
        TenantContext.set(session.get().subject());
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private String bearer(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        return (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7).trim() : null;
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(),
                Map.of("error", "unauthorized", "message", "Sign in to access the developer portal."));
    }
}
