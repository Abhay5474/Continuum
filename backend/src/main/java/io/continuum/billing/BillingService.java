package io.continuum.billing;

import io.continuum.persistence.entity.DeveloperBillingEntity;
import io.continuum.persistence.repository.DeveloperBillingRepository;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Billing &amp; usage metering over the token/cost data the gateway already
 * records.
 *
 * <p>Plans map to a monthly token quota. Usage is <em>derived</em> from
 * {@code gateway_requests} for the current calendar month (no separate usage
 * ledger). The gateway calls {@link #assertWithinQuota} before serving a
 * request; developers over their quota get a clean 402/429 instead of silent
 * overage. Everything defaults to a generous FREE plan, so existing behaviour is
 * unchanged for anyone who never opens the billing page.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    public enum Plan {
        FREE(100_000, 0.0),
        PRO(2_000_000, 20.0),
        SCALE(20_000_000, 99.0);

        public final long monthlyTokenQuota;
        public final double monthlyPriceUsd;

        Plan(long quota, double price) {
            this.monthlyTokenQuota = quota;
            this.monthlyPriceUsd = price;
        }
    }

    /** Thrown when a request would exceed the developer's monthly quota. */
    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String message) {
            super(message);
        }
    }

    /** How an account came to be on its plan. */
    public static final String FREE_TIER = "FREE_TIER";
    public static final String PAID = "PAID";
    public static final String OPERATOR_GRANT = "OPERATOR_GRANT";

    private final DeveloperBillingRepository billing;
    private final GatewayRequestLogRepository requests;
    private final PaymentProvider payments;

    public BillingService(DeveloperBillingRepository billing, GatewayRequestLogRepository requests,
                          PaymentProvider payments) {
        this.billing = billing;
        this.requests = requests;
        this.payments = payments;
    }

    @Transactional
    public DeveloperBillingEntity getOrCreate(String developerId) {
        return billing.findById(developerId)
                .orElseGet(() -> billing.save(new DeveloperBillingEntity(developerId)));
    }

    /**
     * Moves an account between plans.
     *
     * <p>Downgrades apply immediately and need no payment — leaving should never
     * be harder than joining. Upgrades require a settlement, because a plan is an
     * entitlement to spend the operator's money on provider calls; granting one
     * on request alone is how a free account ends up with a 200× quota. Without a
     * configured processor an upgrade is refused outright rather than applied.
     *
     * @param paymentReference the checkout this upgrade was paid under
     */
    @Transactional
    public DeveloperBillingEntity changePlan(String developerId, Plan plan, String paymentReference) {
        DeveloperBillingEntity b = getOrCreate(developerId);
        Plan current = planOf(b);

        if (plan.monthlyPriceUsd <= current.monthlyPriceUsd) {
            b.setPaymentReference(null);
            b.setGrantedBy(null);
            b.setPeriodEnd(null);
            return apply(b, plan, plan == Plan.FREE ? FREE_TIER : PAID, developerId, "downgrade");
        }

        if (!payments.configured()) {
            throw new PaymentProvider.PaymentNotConfiguredException(
                    "Self-serve upgrades are unavailable: no payment processor is configured. "
                            + "Contact the operator to have a plan applied to your account.");
        }
        if (paymentReference == null || paymentReference.isBlank()) {
            throw new PaymentRequiredException("This plan requires payment. Start a checkout first.");
        }
        PaymentProvider.Settlement settled = payments.settlement(developerId, paymentReference)
                .orElseThrow(() -> new PaymentRequiredException(
                        "That payment has not settled, so the plan was not changed."));
        if (settled.plan() != plan) {
            // Otherwise a cheap checkout could be presented to claim a dearer plan.
            throw new PaymentRequiredException(
                    "That payment is for the " + settled.plan() + " plan, not " + plan + ".");
        }
        b.setPaymentReference(settled.reference());
        b.setGrantedBy(null);
        b.setPeriodEnd(settled.periodEnd());
        return apply(b, plan, PAID, developerId, "paid upgrade");
    }

    /**
     * Puts an account on a plan without payment. The operator's escape hatch for
     * manual invoicing, trials and enterprise deals — recorded with who did it,
     * so an unpaid plan is always attributable.
     */
    @Transactional
    public DeveloperBillingEntity grantPlan(String developerId, Plan plan, String grantedBy) {
        DeveloperBillingEntity b = getOrCreate(developerId);
        b.setPaymentReference(null);
        b.setGrantedBy(grantedBy == null ? "operator" : grantedBy);
        return apply(b, plan, plan == Plan.FREE ? FREE_TIER : OPERATOR_GRANT, developerId, "operator grant");
    }

    /** Begins a checkout, or explains why it cannot. */
    public PaymentProvider.Checkout startCheckout(String developerId, Plan plan) {
        if (plan == Plan.FREE) {
            throw new IllegalArgumentException("The free plan does not require a checkout.");
        }
        return payments.startCheckout(developerId, plan);
    }

    private DeveloperBillingEntity apply(DeveloperBillingEntity b, Plan plan, String source,
                                         String developerId, String why) {
        b.setPlan(plan.name());
        b.setMonthlyTokenQuota(plan.monthlyTokenQuota);
        b.setPlanSource(source);
        log.info("Billing: developer {} moved to plan {} ({}, source {})", developerId, plan, why, source);
        return billing.save(b);
    }

    private static Plan planOf(DeveloperBillingEntity b) {
        try {
            return Plan.valueOf(b.getPlan());
        } catch (Exception e) {
            return Plan.FREE;
        }
    }

    /** Raised when an upgrade is attempted without a settled payment. */
    public static class PaymentRequiredException extends RuntimeException {
        public PaymentRequiredException(String message) {
            super(message);
        }
    }

    /** Hard gate the gateway calls before serving. No-op unless over quota. */
    public void assertWithinQuota(String developerId) {
        if (developerId == null) {
            return;
        }
        try {
            DeveloperBillingEntity b = getOrCreate(developerId);
            long used = requests.tokensForDeveloperSince(developerId, monthStart());
            if (used >= b.getMonthlyTokenQuota()) {
                throw new QuotaExceededException("Monthly token quota exceeded ("
                        + used + "/" + b.getMonthlyTokenQuota() + "). Upgrade your plan to continue.");
            }
        } catch (QuotaExceededException e) {
            throw e;
        } catch (Exception e) {
            // Metering must never break a request for an unexpected reason.
            log.debug("quota check skipped for {}: {}", developerId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> usage(String developerId) {
        DeveloperBillingEntity b = getOrCreate(developerId);
        Instant since = monthStart();
        long usedTokens = requests.tokensForDeveloperSince(developerId, since);
        double usedCost = requests.costForDeveloperSince(developerId, since);
        long reqs = requests.countByDeveloperIdAndCreatedAtGreaterThanEqual(developerId, since);
        long quota = b.getMonthlyTokenQuota();
        double fraction = quota == 0 ? 1.0 : Math.min(1.0, (double) usedTokens / quota);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", b.getPlan());
        out.put("planSource", b.getPlanSource());
        out.put("grantedBy", b.getGrantedBy());
        out.put("periodEnd", b.getPeriodEnd());
        out.put("paymentConfigured", payments.configured());
        out.put("paymentProvider", payments.name());
        out.put("monthlyTokenQuota", quota);
        out.put("tokensUsed", usedTokens);
        out.put("tokensRemaining", Math.max(0, quota - usedTokens));
        out.put("usageFraction", fraction);
        out.put("overQuota", usedTokens >= quota);
        out.put("requestsThisPeriod", reqs);
        out.put("costThisPeriodUsd", usedCost);
        out.put("periodStart", since.toString());
        out.put("plans", plans());
        return out;
    }

    public List<Map<String, Object>> plans() {
        return java.util.Arrays.stream(Plan.values()).map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.name());
            m.put("monthlyTokenQuota", p.monthlyTokenQuota);
            m.put("monthlyPriceUsd", p.monthlyPriceUsd);
            return m;
        }).toList();
    }

    @Transactional
    public void deleteFor(String developerId) {
        billing.findById(developerId).ifPresent(billing::delete);
    }

    private static Instant monthStart() {
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
