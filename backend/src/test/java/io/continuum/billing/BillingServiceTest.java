package io.continuum.billing;

import io.continuum.persistence.entity.DeveloperBillingEntity;
import io.continuum.persistence.repository.DeveloperBillingRepository;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Billing plans, usage metering, and quota enforcement. */
class BillingServiceTest {

    private final DeveloperBillingRepository billing = mock(DeveloperBillingRepository.class);
    private final GatewayRequestLogRepository requests = mock(GatewayRequestLogRepository.class);
    private final BillingService service =
            new BillingService(billing, requests, new UnconfiguredPaymentProvider());

    private void withPlan(String dev, DeveloperBillingEntity entity) {
        when(billing.findById(dev)).thenReturn(Optional.of(entity));
        when(billing.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void freePlanIsCreatedByDefaultWithGenerousQuota() {
        when(billing.findById("dev-1")).thenReturn(Optional.empty());
        when(billing.save(any())).thenAnswer(i -> i.getArgument(0));
        var b = service.getOrCreate("dev-1");
        assertEquals("FREE", b.getPlan());
        assertEquals(BillingService.Plan.FREE.monthlyTokenQuota, b.getMonthlyTokenQuota());
    }

    @Test
    void usageReportsTokensRemainingAndFraction() {
        DeveloperBillingEntity b = new DeveloperBillingEntity("dev-1"); // FREE, 100k
        withPlan("dev-1", b);
        when(requests.tokensForDeveloperSince(eq("dev-1"), any())).thenReturn(40_000L);
        when(requests.costForDeveloperSince(eq("dev-1"), any())).thenReturn(0.12);
        when(requests.countByDeveloperIdAndCreatedAtGreaterThanEqual(eq("dev-1"), any())).thenReturn(30L);

        var u = service.usage("dev-1");
        assertEquals(100_000L, u.get("monthlyTokenQuota"));
        assertEquals(40_000L, u.get("tokensUsed"));
        assertEquals(60_000L, u.get("tokensRemaining"));
        assertEquals(0.4, (double) u.get("usageFraction"), 1e-9);
        assertEquals(false, u.get("overQuota"));
    }

    @Test
    void quotaIsEnforcedOnceExceeded() {
        DeveloperBillingEntity b = new DeveloperBillingEntity("dev-1");
        withPlan("dev-1", b);
        when(requests.tokensForDeveloperSince(eq("dev-1"), any())).thenReturn(100_001L);
        assertThrows(BillingService.QuotaExceededException.class,
                () -> service.assertWithinQuota("dev-1"));
    }

    @Test
    void underQuotaAndNullDeveloperPassThrough() {
        DeveloperBillingEntity b = new DeveloperBillingEntity("dev-1");
        withPlan("dev-1", b);
        when(requests.tokensForDeveloperSince(eq("dev-1"), any())).thenReturn(5L);
        assertDoesNotThrow(() -> service.assertWithinQuota("dev-1"));
        assertDoesNotThrow(() -> service.assertWithinQuota(null)); // anonymous/internal
    }

    @Test
    void movingToProRaisesTheQuota() {
        // Self-serve upgrades now require a settled payment, so the quota change
        // is exercised through the operator grant. That an unpaid *self-serve*
        // upgrade is refused is asserted in PlanChangeTest.
        DeveloperBillingEntity b = new DeveloperBillingEntity("dev-1");
        withPlan("dev-1", b);
        var updated = service.grantPlan("dev-1", BillingService.Plan.PRO, "ops@continuum.dev");
        assertEquals("PRO", updated.getPlan());
        assertEquals(BillingService.Plan.PRO.monthlyTokenQuota, updated.getMonthlyTokenQuota());
    }
}
