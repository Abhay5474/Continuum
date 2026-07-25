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
 * Authenticates the operational console APIs (dashboard, gateway stats, DAG
 * traces, context profiler, memory, routing, fault injection, replay).
 *
 * <p>Before this filter existed these endpoints were reachable anonymously, which
 * meant (a) anyone could read the console without an account and (b) endpoints
 * that accepted a {@code developerId} query parameter let a caller name <em>any</em>
 * tenant and read their data. This filter is half of the fix: it requires a valid
 * session. The other half lives in the controllers, which now resolve the tenant
 * from {@link #DEVELOPER_ID_ATTRIBUTE} instead of trusting client input.
 *
 * <p>Two roles are accepted:
 * <ul>
 *   <li>{@code DEVELOPER} — scoped to their own data; the resolved id is attached
 *       as {@link #DEVELOPER_ID_ATTRIBUTE}.</li>
 *   <li>{@code OPERATOR} — the engine operator, who may read engine-wide state.
 *       Flagged via {@link #OPERATOR_ATTRIBUTE}.</li>
 * </ul>
 */
public class ConsoleAuthFilter extends OncePerRequestFilter {

    /** Same attribute name the portal filter uses, so controllers read one key. */
    public static final String DEVELOPER_ID_ATTRIBUTE = PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE;
    public static final String OPERATOR_ATTRIBUTE = "continuum.console.operator";

    private final PortalSessionService sessions;
    private final ObjectMapper mapper;

    public ConsoleAuthFilter(PortalSessionService sessions, ObjectMapper mapper) {
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
        String token = bearer(request);
        Optional<PortalSessionService.Session> session = token == null ? Optional.empty() : sessions.verify(token);
        if (session.isEmpty()) {
            unauthorized(response);
            return;
        }
        PortalSessionService.Session s = session.get();
        if (s.role() == PortalSessionService.Role.OPERATOR) {
            request.setAttribute(OPERATOR_ATTRIBUTE, Boolean.TRUE);
        } else {
            request.setAttribute(DEVELOPER_ID_ATTRIBUTE, s.subject());
        }
        chain.doFilter(request, response);
    }

    private String bearer(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        return (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7).trim() : null;
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(),
                Map.of("error", "unauthorized", "message", "Sign in to view console data."));
    }
}
