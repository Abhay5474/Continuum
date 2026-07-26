package io.continuum.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies a payment provider, defaulting to the one that refuses.
 *
 * <p>Registering a real processor is a matter of contributing another
 * {@link PaymentProvider} bean; this fallback then steps aside. Deliberately a
 * {@code @Bean} rather than a scanned component, because
 * {@link ConditionalOnMissingBean} is only evaluated reliably here.
 */
@Configuration
public class PaymentConfig {

    @Bean
    @ConditionalOnMissingBean(PaymentProvider.class)
    public PaymentProvider unconfiguredPaymentProvider() {
        return new UnconfiguredPaymentProvider();
    }
}
