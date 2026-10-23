package io.continuum.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Keeps the operational endpoints under {@code /actuator} to whoever should
 * see them; health stays public, because the host's health check calls it.
 *
 * <p>Metrics and Prometheus answered anyone on a hosted deployment: every
 * endpoint's traffic and error counts, the JVM's state. They now answer a
 * caller on this machine, or one presenting {@code CONTINUUM_METRICS_TOKEN}
 * as a bearer token when that is set (for a Prometheus scraping a hosted
 * instance). Anything else gets a 404, as for a path that does not exist.
 */
public class MetricsAccessFilter extends OncePerRequestFilter {

    private final byte[] token;

    public MetricsAccessFilter(String token) {
        this.token = token == null || token.isBlank() ? null : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (isPublic(path) || allowed(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    static boolean isPublic(String path) {
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    private boolean allowed(HttpServletRequest request) {
        if (token != null) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")
                    && MessageDigest.isEqual(token, auth.substring(7).trim().getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        // Loopback only when nothing proxied the request in: behind a hosting
        // platform's proxy every caller arrives from a private address.
        if (request.getHeader("X-Forwarded-For") != null || request.getHeader("Forwarded") != null) {
            return false;
        }
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }
}
