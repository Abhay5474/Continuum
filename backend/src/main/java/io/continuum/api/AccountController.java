package io.continuum.api;

import io.continuum.portal.AccountService;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Account &amp; settings — session-scoped via {@link PortalAuthFilter}. Change
 * password / email, delete account (GDPR), and manage team invites.
 */
@RestController
@RequestMapping("/api/portal/developer/account")
public class AccountController {

    private final AccountService account;

    public AccountController(AccountService account) {
        this.account = account;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @PostMapping("/password")
    public Map<String, Object> changePassword(HttpServletRequest req, @RequestBody PasswordRequest body) {
        account.changePassword(dev(req), body.currentPassword(), body.newPassword());
        return Map.of("ok", true);
    }

    @PutMapping("/email")
    public Map<String, Object> changeEmail(HttpServletRequest req, @RequestBody EmailRequest body) {
        account.changeEmail(dev(req), body.email());
        return Map.of("ok", true, "email", body.email());
    }

    @DeleteMapping
    public Map<String, Object> deleteAccount(HttpServletRequest req) {
        account.deleteAccount(dev(req));
        return Map.of("ok", true, "deleted", true);
    }

    @GetMapping("/invites")
    public List<Map<String, Object>> invites(HttpServletRequest req) {
        return account.invites(dev(req));
    }

    @PostMapping("/invites")
    public Map<String, Object> invite(HttpServletRequest req, @RequestBody InviteRequest body) {
        return account.invite(dev(req), body.email());
    }

    public record PasswordRequest(String currentPassword, String newPassword) {
    }

    public record EmailRequest(String email) {
    }

    public record InviteRequest(String email) {
    }
}
