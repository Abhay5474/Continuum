package io.continuum.api;

import io.continuum.portal.PortalAuthFilter;
import io.continuum.webhook.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The signed-in account's webhook endpoints: add, change, test, see deliveries. */
@RestController
@RequestMapping("/api/portal/developer/webhooks")
public class WebhookController {

    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    private static String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest req) {
        return Map.of("endpoints", webhooks.list(dev(req)), "events", WebhookService.EVENTS);
    }

    /** The secret is in this answer and no other. */
    @PostMapping
    public WebhookService.Created create(@RequestBody CreateRequest body, HttpServletRequest req) {
        return webhooks.create(dev(req), body.url(), body.description(), body.events());
    }

    @PutMapping("/{id}")
    public WebhookService.Endpoint update(@PathVariable long id, @RequestBody UpdateRequest body,
                                          HttpServletRequest req) {
        return webhooks.update(dev(req), id, body.enabled(), body.events(), body.description());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest req) {
        webhooks.delete(dev(req), id);
        return Map.of("deleted", id);
    }

    @PostMapping("/{id}/rotate-secret")
    public Map<String, Object> rotate(@PathVariable long id, HttpServletRequest req) {
        return Map.of("secret", webhooks.rotateSecret(dev(req), id));
    }

    /** Sends a {@code ping} through the outbox, as a real event would go. */
    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable long id, HttpServletRequest req) {
        return Map.of("queued", true, "idempotencyKey", webhooks.ping(dev(req), id));
    }

    @GetMapping("/{id}/deliveries")
    public List<WebhookService.Delivery> deliveries(@PathVariable long id,
                                                    @RequestParam(defaultValue = "25") int limit,
                                                    HttpServletRequest req) {
        return webhooks.deliveries(dev(req), id, limit);
    }

    public record CreateRequest(String url, String description, List<String> events) {
    }

    public record UpdateRequest(Boolean enabled, List<String> events, String description) {
    }
}
