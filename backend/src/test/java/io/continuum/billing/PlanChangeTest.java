package io.continuum.billing;

import io.continuum.persistence.entity.DeveloperBillingEntity;
import io.continuum.persistence.repository.DeveloperBillingRepository;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A paid plan is an entitlement to spend the operator's money on provider calls,
 * so the only question that matters here is whether one can be obtained without
 * paying for it.
 */
class PlanChangeTest {

    private static final String DEV = "dev-1";

    private DeveloperBillingRepository repo;
    private DeveloperBillingEntity row;

    @BeforeEach
    void setUp() {
        repo = mock(DeveloperBillingRepository.class);
        row = new DeveloperBillingEntity(DEV);
        when(repo.findById(DEV)).thenReturn(Optional.of(row));
        when(repo.save(any(DeveloperBillingEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private BillingService serviceWith(PaymentProvider payments) {
        return new BillingService(repo, mock(GatewayRequestLogRepository.class), payments);
    }

    private static PaymentProvider unconfigured() {
        return new UnconfiguredPaymentProvider();
    }

    /** A processor that settles exactly one reference, for one plan. */
    private static PaymentProvider settling(String reference, BillingService.Plan plan) {
        PaymentProvider p = mock(PaymentProvider.class);
        when(p.configured()).thenReturn(true);
        when(p.name()).thenReturn("test");
        when(p.settlement(anyString(), anyString())).thenReturn(Optional.empty());
        when(p.settlement(DEV, reference)).thenReturn(Optional.of(
                new PaymentProvider.Settlement(reference, plan, Instant.now().plusSeconds(2_592_000))));
        return p;
    }

    @Test
    @DisplayName("without a payment processor an upgrade is refused, not granted")
    void upgradeRefusedWhenPaymentsUnconfigured() {
        BillingService billing = serviceWith(unconfigured());

        assertThatThrownBy(() -> billing.changePlan(DEV, BillingService.Plan.SCALE, null))
                .isInstanceOf(PaymentProvider.PaymentNotConfiguredException.class);

        assertThat(row.getPlan()).isEqualTo("FREE");
        assertThat(row.getMonthlyTokenQuota()).isEqualTo(BillingService.Plan.FREE.monthlyTokenQuota);
    }

    @Test
    @DisplayName("an upgrade with no payment reference is refused")
    void upgradeNeedsAReference() {
        BillingService billing = serviceWith(settling("pay_1", BillingService.Plan.SCALE));

        assertThatThrownBy(() -> billing.changePlan(DEV, BillingService.Plan.SCALE, null))
                .isInstanceOf(BillingService.PaymentRequiredException.class);
        assertThat(row.getPlan()).isEqualTo("FREE");
    }

    @Test
    @DisplayName("an unsettled reference is refused")
    void unsettledReferenceIsRefused() {
        BillingService billing = serviceWith(settling("pay_1", BillingService.Plan.SCALE));

        assertThatThrownBy(() -> billing.changePlan(DEV, BillingService.Plan.SCALE, "pay_forged"))
                .isInstanceOf(BillingService.PaymentRequiredException.class);
        assertThat(row.getPlan()).isEqualTo("FREE");
    }

    @Test
    @DisplayName("a payment for a cheaper plan cannot claim a dearer one")
    void settlementMustMatchThePlanRequested() {
        BillingService billing = serviceWith(settling("pay_pro", BillingService.Plan.PRO));

        assertThatThrownBy(() -> billing.changePlan(DEV, BillingService.Plan.SCALE, "pay_pro"))
                .isInstanceOf(BillingService.PaymentRequiredException.class)
                .hasMessageContaining("PRO");
        assertThat(row.getPlan()).isEqualTo("FREE");
    }

    @Test
    @DisplayName("a settled payment applies the plan and records how it was obtained")
    void settledPaymentUpgrades() {
        BillingService billing = serviceWith(settling("pay_1", BillingService.Plan.SCALE));

        billing.changePlan(DEV, BillingService.Plan.SCALE, "pay_1");

        assertThat(row.getPlan()).isEqualTo("SCALE");
        assertThat(row.getMonthlyTokenQuota()).isEqualTo(BillingService.Plan.SCALE.monthlyTokenQuota);
        assertThat(row.getPlanSource()).isEqualTo(BillingService.PAID);
        assertThat(row.getPaymentReference()).isEqualTo("pay_1");
        assertThat(row.getPeriodEnd()).isNotNull();
    }

    @Test
    @DisplayName("downgrading needs no payment — leaving is never harder than joining")
    void downgradeIsAlwaysAllowed() {
        BillingService billing = serviceWith(settling("pay_1", BillingService.Plan.SCALE));
        billing.changePlan(DEV, BillingService.Plan.SCALE, "pay_1");

        billing.changePlan(DEV, BillingService.Plan.FREE, null);

        assertThat(row.getPlan()).isEqualTo("FREE");
        assertThat(row.getPlanSource()).isEqualTo(BillingService.FREE_TIER);
        assertThat(row.getPaymentReference()).isNull();
    }

    @Test
    @DisplayName("an operator can grant a plan, and the grant is attributed")
    void operatorGrantIsRecorded() {
        BillingService billing = serviceWith(unconfigured());

        billing.grantPlan(DEV, BillingService.Plan.PRO, "ops@continuum.dev");

        assertThat(row.getPlan()).isEqualTo("PRO");
        assertThat(row.getPlanSource()).isEqualTo(BillingService.OPERATOR_GRANT);
        assertThat(row.getGrantedBy()).isEqualTo("ops@continuum.dev");
    }
}
