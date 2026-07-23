package io.continuum.api;

import io.continuum.billing.BillingService;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Billing &amp; usage metering — session-scoped via {@link PortalAuthFilter}.
 * Mock-Stripe: shows the current plan, this month's token usage vs quota, and
 * lets the developer switch plans (no real payment).
 */
@RestController
@RequestMapping("/api/portal/developer/billing")
public class BillingController {

    private final BillingService billing;

    public BillingController(BillingService billing) {
        this.billing = billing;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping
    public Map<String, Object> usage(HttpServletRequest req) {
        return billing.usage(dev(req));
    }

    @PutMapping("/plan")
    public Map<String, Object> setPlan(HttpServletRequest req, @RequestBody PlanRequest body) {
        BillingService.Plan plan;
        try {
            plan = BillingService.Plan.valueOf(body.plan().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown plan: " + body.plan());
        }
        billing.setPlan(dev(req), plan);
        return billing.usage(dev(req));
    }

    public record PlanRequest(String plan) {
    }
}
