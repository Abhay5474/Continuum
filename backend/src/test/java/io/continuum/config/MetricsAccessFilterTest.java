package io.continuum.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsAccessFilterTest {

    private int status(MetricsAccessFilter f, String path, String remote, String auth, boolean proxied) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRemoteAddr(remote);
        if (auth != null) req.addHeader("Authorization", auth);
        if (proxied) req.addHeader("X-Forwarded-For", "203.0.113.9");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, res, chain);
        return chain.getRequest() == null ? res.getStatus() : 200;
    }

    @Test
    void healthIsPublicMetricsAreNot() throws Exception {
        MetricsAccessFilter f = new MetricsAccessFilter("");
        assertThat(status(f, "/actuator/health", "203.0.113.9", null, false)).isEqualTo(200);
        assertThat(status(f, "/actuator/health/readiness", "203.0.113.9", null, true)).isEqualTo(200);
        assertThat(status(f, "/actuator/prometheus", "203.0.113.9", null, false)).isEqualTo(404);
        assertThat(status(f, "/actuator/metrics", "127.0.0.1", null, false)).isEqualTo(200);
        // Behind a proxy every caller looks local; that is not this machine.
        assertThat(status(f, "/actuator/metrics", "127.0.0.1", null, true)).isEqualTo(404);
    }

    @Test
    void theTokenOpensMetricsFromAnywhere() throws Exception {
        MetricsAccessFilter f = new MetricsAccessFilter("s3cret");
        assertThat(status(f, "/actuator/prometheus", "203.0.113.9", "Bearer s3cret", true)).isEqualTo(200);
        assertThat(status(f, "/actuator/prometheus", "203.0.113.9", "Bearer wrong", true)).isEqualTo(404);
    }
}
