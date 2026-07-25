package io.continuum.portal;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tenancy rules for the console APIs. These lock in the fix for the cross-tenant
 * read: the tenant comes from the session, and one developer may never read
 * another's records.
 */
class RequestScopeTest {

    private MockHttpServletRequest asDeveloper(String id) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(ConsoleAuthFilter.DEVELOPER_ID_ATTRIBUTE, id);
        return req;
    }

    private MockHttpServletRequest asOperator() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(ConsoleAuthFilter.OPERATOR_ATTRIBUTE, Boolean.TRUE);
        return req;
    }

    @Test
    void developerIdComesFromTheSession() {
        assertThat(RequestScope.developerId(asDeveloper("dev_a"))).isEqualTo("dev_a");
    }

    @Test
    void operatorHasNoTenantAndIsFlagged() {
        MockHttpServletRequest op = asOperator();
        assertThat(RequestScope.developerId(op)).isNull();
        assertThat(RequestScope.isOperator(op)).isTrue();
    }

    @Test
    void anonymousRequestHasNoTenant() {
        MockHttpServletRequest anon = new MockHttpServletRequest();
        assertThat(RequestScope.developerId(anon)).isNull();
        assertThat(RequestScope.isOperator(anon)).isFalse();
    }

    @Test
    void developerMayReadTheirOwnRecord() {
        assertThatCode(() -> RequestScope.requireOwner(asDeveloper("dev_a"), "dev_a"))
                .doesNotThrowAnyException();
    }

    @Test
    void developerMayNotReadAnotherTenantsRecord() {
        assertThatThrownBy(() -> RequestScope.requireOwner(asDeveloper("dev_a"), "dev_b"))
                .isInstanceOf(RequestScope.ForbiddenException.class);
    }

    @Test
    void developerMayNotReadAnUnownedSystemRecord() {
        // A null owner is a system/legacy row — operator-only, never tenant-readable.
        assertThatThrownBy(() -> RequestScope.requireOwner(asDeveloper("dev_a"), null))
                .isInstanceOf(RequestScope.ForbiddenException.class);
    }

    @Test
    void operatorMayReadAnyRecord() {
        assertThatCode(() -> RequestScope.requireOwner(asOperator(), "dev_b")).doesNotThrowAnyException();
        assertThatCode(() -> RequestScope.requireOwner(asOperator(), null)).doesNotThrowAnyException();
    }

    @Test
    void anonymousMayNotReadAnything() {
        assertThatThrownBy(() -> RequestScope.requireOwner(new MockHttpServletRequest(), "dev_a"))
                .isInstanceOf(RequestScope.ForbiddenException.class);
    }
}
