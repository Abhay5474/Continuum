package io.continuum.api;

import io.continuum.billing.BillingService;
import io.continuum.billing.PaymentProvider;
import io.continuum.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Billing &amp; usage — session-scoped via {@link PortalAuthFilter}.
 *
 * <p>Changing plan is not the same operation in both directions. Downgrading is
 * the developer's to do and applies at once. Upgrading needs a settled payment,
 * so it goes through a checkout; on a deployment with no processor configured it
 * is refused rather than granted.
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

    private static BillingService.Plan parse(String plan) {
        try {
            return BillingService.Plan.valueOf(plan.toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown plan: " + plan);
        }
    }

    @GetMapping
    public Map<String, Object> usage(HttpServletRequest req) {
        return billing.usage(dev(req));
    }

    @PutMapping("/plan")
    public Map<String, Object> setPlan(HttpServletRequest req, @RequestBody PlanRequest body) {
        billing.changePlan(dev(req), parse(body.plan()), body.paymentReference());
        return billing.usage(dev(req));
    }

    /** Starts a checkout for a paid plan; 402 when this deployment cannot charge. */
    @PostMapping("/checkout")
    public PaymentProvider.Checkout checkout(HttpServletRequest req, @RequestBody PlanRequest body) {
        return billing.startCheckout(dev(req), parse(body.plan()));
    }

    public record PlanRequest(String plan, String paymentReference) {
    }
}
