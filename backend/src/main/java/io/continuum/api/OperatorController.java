package io.continuum.api;

import io.continuum.portal.OperatorService;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Operator access tied to a person's account — see {@link OperatorService}.
 * Behind the portal filter: every call is made by a signed-in person.
 */
@RestController
@RequestMapping("/api/portal/developer/operator")
public class OperatorController {

    private final OperatorService operators;

    public OperatorController(OperatorService operators) {
        this.operators = operators;
    }

    /** The person signed in — operator access belongs to people, not to the account they work in. */
    private String actor(HttpServletRequest req) {
        Object a = req.getAttribute(PortalAuthFilter.ACTOR_ID_ATTRIBUTE);
        return (String) (a == null ? req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE) : a);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return operators.status(actor(req));
    }

    @PostMapping("/claim")
    public Map<String, Object> claim(HttpServletRequest req, @RequestBody ClaimRequest body) {
        return Map.of("role", "OPERATOR", "sessionToken", operators.claim(actor(req), body.code()));
    }

    @PostMapping("/elevate")
    public Map<String, Object> elevate(HttpServletRequest req, @RequestBody PasswordRequest body) {
        return Map.of("role", "OPERATOR", "sessionToken", operators.elevate(actor(req), body.password()));
    }

    @GetMapping("/grants")
    public List<Map<String, Object>> grants(HttpServletRequest req) {
        if (!operators.isOperator(actor(req))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Only an operator can see who operates.");
        }
        return operators.list();
    }

    @PostMapping("/grants")
    public Map<String, Object> grant(HttpServletRequest req, @RequestBody GrantRequest body) {
        return operators.grant(actor(req), body.password(), body.email());
    }

    @PostMapping("/grants/{developerId}/revoke")
    public Map<String, Object> revoke(HttpServletRequest req, @PathVariable String developerId,
                                      @RequestBody PasswordRequest body) {
        return operators.revoke(actor(req), body.password(), developerId);
    }

    public record ClaimRequest(String code) {
    }

    public record PasswordRequest(String password) {
    }

    public record GrantRequest(String email, String password) {
    }
}
