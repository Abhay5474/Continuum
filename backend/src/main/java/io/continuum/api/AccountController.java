package io.continuum.api;

import io.continuum.portal.AccountService;
import io.continuum.portal.PortalAuthFilter;
import io.continuum.portal.PortalSessionService;
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
    private final PortalSessionService sessions;

    public AccountController(AccountService account, PortalSessionService sessions) {
        this.account = account;
        this.sessions = sessions;
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

    /** Withdraws an invite that has not been accepted. */
    @DeleteMapping("/invites/{id}")
    public Map<String, Object> revokeInvite(HttpServletRequest req, @PathVariable long id) {
        account.revokeInvite(dev(req), id);
        return Map.of("revoked", true);
    }

    /** Everyone who can sign in to this account. */
    @GetMapping("/members")
    public List<Map<String, Object>> members(HttpServletRequest req) {
        return account.members(dev(req));
    }

    /**
     * What an invite is for, before accepting it. Open by necessity: the invitee
     * has no account yet, which is the point.
     */
    @GetMapping("/invites/preview")
    public Map<String, Object> previewInvite(@RequestParam String token) {
        return account.previewInvite(token);
    }

    /**
     * Accepts an invite: creates the teammate's login and joins them to the
     * inviting account, returning a session for it.
     */
    @PostMapping("/invites/accept")
    public Map<String, Object> acceptInvite(@RequestBody AcceptRequest body) {
        AccountService.Accepted accepted = account.acceptInvite(body.token(), body.name(), body.password());
        return Map.of(
                "developerId", accepted.accountId(),
                "name", accepted.name(),
                "email", accepted.email(),
                "sessionToken", sessions.issue(accepted.accountId(), PortalSessionService.Role.DEVELOPER));
    }

    public record AcceptRequest(String token, String name, String password) {
    }

    public record PasswordRequest(String currentPassword, String newPassword) {
    }

    public record EmailRequest(String email) {
    }

    public record InviteRequest(String email) {
    }
}
