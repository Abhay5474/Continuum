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
 * Billing &amp; usage metering — a mock-Stripe layer over the token/cost data the
 * gateway already records.
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

    /** Mock plan catalogue. */
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

    private final DeveloperBillingRepository billing;
    private final GatewayRequestLogRepository requests;

    public BillingService(DeveloperBillingRepository billing, GatewayRequestLogRepository requests) {
        this.billing = billing;
        this.requests = requests;
    }

    @Transactional
    public DeveloperBillingEntity getOrCreate(String developerId) {
        return billing.findById(developerId)
                .orElseGet(() -> billing.save(new DeveloperBillingEntity(developerId)));
    }

    /** Mock upgrade/downgrade (no real payment) — sets plan + its quota. */
    @Transactional
    public DeveloperBillingEntity setPlan(String developerId, Plan plan) {
        DeveloperBillingEntity b = getOrCreate(developerId);
        b.setPlan(plan.name());
        b.setMonthlyTokenQuota(plan.monthlyTokenQuota);
        log.info("Billing: developer {} moved to plan {}", developerId, plan);
        return billing.save(b);
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
