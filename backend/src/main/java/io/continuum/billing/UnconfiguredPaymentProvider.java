package io.continuum.billing;

import java.util.Optional;

/**
 * The default when no payment processor is wired up: it refuses.
 *
 * <p>This is the whole point of the class. A deployment with no way to charge
 * must not hand out paid plans — before this, one call to the plan endpoint
 * moved an account from the free tier to the largest paid tier, a 200× quota
 * increase, for nothing. Refusing is the correct behaviour, and a developer who
 * needs a plan without self-serve payment can be granted one by the operator.
 */
public class UnconfiguredPaymentProvider implements PaymentProvider {

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public String name() {
        return "none";
    }

    @Override
    public Checkout startCheckout(String developerId, BillingService.Plan plan) {
        throw new PaymentNotConfiguredException(
                "Self-serve upgrades are unavailable: this deployment has no payment processor configured. "
                        + "Contact the operator to have a plan applied to your account.");
    }

    @Override
    public Optional<Settlement> settlement(String developerId, String reference) {
        return Optional.empty();
    }
}
