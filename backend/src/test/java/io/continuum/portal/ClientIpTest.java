package io.continuum.portal;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The address the sign-in limiter keys on. It used to be the leftmost
 * X-Forwarded-For entry, which the client writes: rotating it gave each
 * password guess a fresh allowance.
 */
class ClientIpTest {

    private static MockHttpServletRequest req(String remote, String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remote);
        if (xff != null) {
            r.addHeader("X-Forwarded-For", xff);
        }
        return r;
    }

    @Test
    void aDirectClientCannotChooseItsAddress() {
        assertEquals("203.0.113.9", RateLimitFilter.clientIp(req("203.0.113.9", "10.1.1.1")));
    }

    @Test
    void behindAProxyTheProxysOwnEntryCountsNotTheClients() {
        // Client wrote "1.2.3.4"; the proxy appended the real address.
        assertEquals("198.51.100.7", RateLimitFilter.clientIp(req("10.0.0.5", "1.2.3.4, 198.51.100.7")));
        assertEquals("198.51.100.7", RateLimitFilter.clientIp(req("127.0.0.1", "198.51.100.7")));
    }

    @Test
    void noHeaderMeansTheConnection() {
        assertEquals("10.0.0.5", RateLimitFilter.clientIp(req("10.0.0.5", null)));
    }
}
