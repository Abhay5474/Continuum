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

/**
 * Caps how fast a caller can hit the API.
 *
 * <p>Two tiers, because the risks differ. Sign-in and sign-up are limited hard
 * and by source address: they are reachable without credentials, and each
 * attempt costs 120,000 rounds of PBKDF2, so an unlimited login endpoint is both
 * a way to guess passwords and a way to exhaust the CPU. Everything else is
 * limited per authenticated caller, which is generous enough that ordinary use —
 * including a console polling several endpoints every few seconds — never
 * notices.
 *
 * <p>Refusals answer 429 with {@code Retry-After}, so a client can back off
 * properly instead of hammering.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** Credentials are checked here, so this is deliberately tight. */
    private static final int AUTH_BURST = 10;
    private static final double AUTH_PER_MINUTE = 5;

    /** Ordinary API use, per authenticated caller. */
    private static final int API_BURST = 120;
    private static final double API_PER_MINUTE = 600;

    private final RateLimiter limiter;
    private final PortalSessionService sessions;
    private final ObjectMapper mapper;

    public RateLimitFilter(RateLimiter limiter, PortalSessionService sessions, ObjectMapper mapper) {
        this.limiter = limiter;
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
        boolean credentialRoute = isCredentialRoute(path);

        String key;
        int burst;
        double perMinute;
        if (credentialRoute) {
            // Keyed by source address: there is no account yet to key on, and the
            // point is to stop one source working through a list of them.
            key = "auth:" + clientIp(request);
            burst = AUTH_BURST;
            perMinute = AUTH_PER_MINUTE;
        } else {
            key = "api:" + callerIdentity(request);
            burst = API_BURST;
            perMinute = API_PER_MINUTE;
        }

        RateLimiter.Decision d = limiter.take(key, burst, perMinute);
        response.setHeader("X-RateLimit-Remaining", String.valueOf(d.remaining()));
        if (!d.allowed()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(d.retryAfterSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getWriter(), Map.of(
                    "error", "rate_limited",
                    "message", "Too many requests. Retry in " + d.retryAfterSeconds() + "s.",
                    "retryAfterSeconds", d.retryAfterSeconds()));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isCredentialRoute(String path) {
        return path.endsWith("/login") || path.endsWith("/signup")
                || path.endsWith("/account/password") || path.contains("/operator/login");
    }

    /**
     * The account when we can identify one, otherwise the source address.
     *
     * <p>Deriving it from the session rather than the client's own claim matters:
     * a header a caller controls would let them mint a fresh bucket per request.
     */
    private String callerIdentity(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7).trim();
            var session = sessions.verify(token);
            if (session.isPresent()) {
                return "s:" + session.get().subject();
            }
            // An API key: the prefix is stable per key without revealing it.
            if (token.startsWith("cnt_")) {
                return "k:" + token.substring(0, Math.min(16, token.length()));
            }
        }
        return "ip:" + clientIp(request);
    }

    /**
     * The client address, honouring one hop of {@code X-Forwarded-For}.
     *
     * <p>That header is client-supplied and trivially spoofed, so it is only ever
     * a limiting key, never an authorisation input. Behind a proxy that rewrites
     * it, this is the real client; without a proxy, a spoofer can spread their
     * attempts — which is why credential routes are also slow by construction.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
