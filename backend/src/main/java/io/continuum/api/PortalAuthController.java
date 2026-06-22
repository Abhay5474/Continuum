package io.continuum.api;

import io.continuum.portal.PortalService;
import io.continuum.portal.PortalSessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Open authentication endpoints for the two-tier portal: developer signup/login
 * (self-service) and operator login (exchanges the platform admin token for an
 * OPERATOR session). These routes are intentionally NOT behind the portal filter.
 */
@RestController
@RequestMapping("/api/portal")
public class PortalAuthController {

    private final PortalService portal;
    private final PortalSessionService sessions;
    private final String adminToken;

    public PortalAuthController(PortalService portal, PortalSessionService sessions,
                                @Value("${CONTINUUM_ADMIN_TOKEN:}") String adminToken) {
        this.portal = portal;
        this.sessions = sessions;
        this.adminToken = adminToken == null ? "" : adminToken;
    }

    @PostMapping("/developer/signup")
    public PortalService.LoginResult signup(@RequestBody SignupRequest req) {
        return portal.signup(req.name(), req.email(), req.password());
    }

    @PostMapping("/developer/login")
    public PortalService.LoginResult login(@RequestBody LoginRequest req) {
        return portal.login(req.email(), req.password());
    }

    /** Operator login: present the platform admin token, receive an OPERATOR session. */
    @PostMapping("/operator/login")
    public ResponseEntity<?> operatorLogin(@RequestBody OperatorLogin req) {
        boolean ok = adminToken.isBlank() // dev mode: open
                || (req.token() != null && constantTimeEquals(req.token(), adminToken));
        if (!ok) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized", "message", "Invalid operator token"));
        }
        return ResponseEntity.ok(Map.of(
                "role", "OPERATOR",
                "sessionToken", sessions.issue("operator", PortalSessionService.Role.OPERATOR),
                "devMode", adminToken.isBlank()));
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public record SignupRequest(String name, String email, String password) {
    }

    public record LoginRequest(String email, String password) {
    }

    public record OperatorLogin(String token) {
    }
}
