package io.continuum.chaos;

import io.continuum.portal.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fault injection must not escape the tenant that armed it.
 *
 * <p>This is the property that turns a drill from a liability into a feature: on
 * a shared engine, a global switch means one customer testing their failover
 * degrades everybody else's traffic.
 */
class ChaosTenantScopingTest {

    private final ChaosMonkey chaos = new ChaosMonkey();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a tenant's provider-down does not reach another tenant")
    void providerDownIsScoped() {
        chaos.setPrimaryProviderDown("dev-a", true);

        TenantContext.set("dev-a");
        assertThat(chaos.isPrimaryProviderDown()).isTrue();

        TenantContext.set("dev-b");
        assertThat(chaos.isPrimaryProviderDown()).isFalse();

        // Engine work with no tenant attached is likewise unaffected.
        TenantContext.clear();
        assertThat(chaos.isPrimaryProviderDown()).isFalse();
    }

    @Test
    @DisplayName("the operator's global profile reaches everyone")
    void globalProfileApplies() {
        chaos.setPrimaryProviderDown(null, true);

        TenantContext.set("dev-a");
        assertThat(chaos.isPrimaryProviderDown()).isTrue();
        TenantContext.set("dev-b");
        assertThat(chaos.isPrimaryProviderDown()).isTrue();
    }

    @Test
    @DisplayName("a tenant's certain activity failure spares other tenants")
    void activityFailureIsScoped() {
        chaos.setActivityFailureRate("dev-a", 1.0);

        TenantContext.set("dev-a");
        assertThatThrownBy(() -> chaos.maybeFailActivity("payment"))
                .hasMessageContaining("injected activity failure");

        TenantContext.set("dev-b");
        assertThatNoException().isThrownBy(() -> chaos.maybeFailActivity("payment"));
    }

    @Test
    @DisplayName("a tenant's scheduled crash only crashes their own worker")
    void crashIsScoped() {
        chaos.scheduleCrashAfter("dev-a", 1);

        TenantContext.set("dev-b");
        assertThatNoException().isThrownBy(() -> chaos.maybeFailActivity("echo"));

        TenantContext.set("dev-a");
        assertThatThrownBy(() -> chaos.maybeFailActivity("echo"))
                .isInstanceOf(ChaosMonkey.SimulatedCrashError.class);
    }

    @Test
    @DisplayName("reset clears only the caller's own profile")
    void resetIsScoped() {
        chaos.setPrimaryProviderDown("dev-a", true);
        chaos.setPrimaryProviderDown("dev-b", true);

        chaos.reset("dev-a");

        TenantContext.set("dev-a");
        assertThat(chaos.isPrimaryProviderDown()).isFalse();
        TenantContext.set("dev-b");
        assertThat(chaos.isPrimaryProviderDown()).isTrue();
    }

    @Test
    @DisplayName("state reports the caller's own armed faults, not the engine's")
    void stateIsScoped() {
        chaos.setActivityLatencyMs("dev-a", 250);

        assertThat(chaos.state("dev-a").activityLatencyMs()).isEqualTo(250);
        assertThat(chaos.state("dev-b").activityLatencyMs()).isZero();
        assertThat(chaos.state(null).activityLatencyMs()).isZero();
        assertThat(chaos.state("dev-a").scope()).isEqualTo("account");
        assertThat(chaos.state(null).scope()).isEqualTo("engine");
    }
}
