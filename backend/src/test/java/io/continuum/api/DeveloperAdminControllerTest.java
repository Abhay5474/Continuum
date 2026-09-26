package io.continuum.api;

import io.continuum.billing.BillingService;
import io.continuum.developer.AdminTokenFilter;
import io.continuum.developer.DeveloperService;
import io.continuum.persistence.entity.DeveloperEntity;
import io.continuum.portal.RequestScope;
import io.continuum.vault.CredentialVaultService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeveloperAdminControllerTest {

    private final DeveloperService developers = mock(DeveloperService.class);
    private final BillingService billing = mock(BillingService.class);
    private final DeveloperAdminController controller =
            new DeveloperAdminController(developers, mock(CredentialVaultService.class), billing);

    @Test
    void anAccountNeedsANameAndARealAddress() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.create(new DeveloperAdminController.CreateDeveloper("", "")));
        assertThrows(IllegalArgumentException.class,
                () -> controller.create(new DeveloperAdminController.CreateDeveloper("Ana", "not-an-email")));
        assertThrows(IllegalArgumentException.class,
                () -> controller.create(new DeveloperAdminController.CreateDeveloper("   ", "ana@example.com")));
        verify(developers, never()).createDeveloper(anyString(), anyString());

        controller.create(new DeveloperAdminController.CreateDeveloper("  Ana ", " ana@example.com "));
        verify(developers).createDeveloper("Ana", "ana@example.com");
    }

    @Test
    void aPlanForAnAccountThatDoesNotExistIsNotFound() {
        when(developers.find("nope")).thenReturn(Optional.empty());
        assertThrows(RequestScope.NotFoundException.class,
                () -> controller.grantPlan("nope", new DeveloperAdminController.GrantPlan("PRO", null),
                        new MockHttpServletRequest()));
        verify(billing, never()).grantPlan(any(), any(), any());
    }

    @Test
    void aGrantIsRecordedAgainstTheOperatorWhoMadeIt() {
        DeveloperEntity customer = new DeveloperEntity("Customer", "c@example.com");
        DeveloperEntity operator = new DeveloperEntity("Op", "op@example.com");
        when(developers.find("dev-c")).thenReturn(Optional.of(customer));
        when(developers.find("dev-op")).thenReturn(Optional.of(operator));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(AdminTokenFilter.ACTOR_ATTRIBUTE, "dev-op");
        when(billing.grantPlan(any(), any(), any())).thenThrow(new StopHere());

        assertThrows(StopHere.class, () -> controller.grantPlan("dev-c",
                new DeveloperAdminController.GrantPlan("PRO", "someone-else"), req));
        verify(billing).grantPlan(eq("dev-c"), eq(BillingService.Plan.PRO), eq("op@example.com"));
    }

    private static final class StopHere extends RuntimeException {
    }
}
