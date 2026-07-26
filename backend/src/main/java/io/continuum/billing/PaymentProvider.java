package io.continuum.billing;

import java.util.Optional;

/**
 * How a paid plan gets paid for.
 *
 * <p>Kept as a port with one honest implementation rather than a stubbed
 * integration: a fake that pretends to charge is the same bug as no check at
 * all, because the plan still gets granted for free. Until a real processor is
 * configured, {@link #configured()} is false and upgrades are refused with a
 * clear 402 rather than quietly succeeding.
 *
 * <p>Adding a processor means implementing this interface and registering it as
 * a bean; nothing in {@link BillingService} needs to change.
 */
public interface PaymentProvider {

    /** Whether this deployment can actually take money. */
    boolean configured();

    /** Name shown to a developer, e.g. "stripe". */
    String name();

    /**
     * Begins a checkout for {@code plan}.
     *
     * @return where to send the developer to pay
     * @throws PaymentNotConfiguredException when no processor is available
     */
    Checkout startCheckout(String developerId, BillingService.Plan plan);

    /**
     * The settlement behind {@code reference}, if it has been paid.
     *
     * <p>Consulted before a plan is granted. An unsettled or unknown reference
     * returns empty, which keeps the developer on their current plan.
     */
    Optional<Settlement> settlement(String developerId, String reference);

    /** @param url where the developer completes payment */
    record Checkout(String reference, String url) {
    }

    /** @param plan the plan actually paid for, which is what gets granted */
    record Settlement(String reference, BillingService.Plan plan, java.time.Instant periodEnd) {
    }

    /** Raised when an upgrade is attempted on a deployment that cannot charge. */
    class PaymentNotConfiguredException extends RuntimeException {
        public PaymentNotConfiguredException(String message) {
            super(message);
        }
    }
}
