package io.continuum.declarative;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step URLs are supplied by customers and called by the server, so without a
 * target check the engine would be a confused deputy able to reach internal
 * services and cloud metadata endpoints.
 */
class HttpStepActivityTargetTest {

    private final HttpStepActivity guarded = new HttpStepActivity(new ObjectMapper(), false);
    private final HttpStepActivity permissive = new HttpStepActivity(new ObjectMapper(), true);

    @Test
    void allowsAPublicTarget() {
        // An address literal, so the check is exercised without depending on DNS
        // being reachable from the test environment.
        assertThatCode(() -> guarded.validateTarget("https://93.184.216.34/hook"))
                .doesNotThrowAnyException();
    }

    @Test
    void blocksLoopback() {
        assertThatThrownBy(() -> guarded.validateTarget("http://127.0.0.1:8080/internal"))
                .isInstanceOf(HttpStepActivity.NonRetryable.class)
                .hasMessageContaining("private address");
    }

    @Test
    void blocksPrivateRanges() {
        for (String host : new String[] {"http://10.0.0.5/x", "http://192.168.1.10/x", "http://172.16.0.9/x"}) {
            assertThatThrownBy(() -> guarded.validateTarget(host))
                    .isInstanceOf(HttpStepActivity.NonRetryable.class);
        }
    }

    @Test
    void blocksCloudMetadataLinkLocal() {
        assertThatThrownBy(() -> guarded.validateTarget("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(HttpStepActivity.NonRetryable.class);
    }

    @Test
    void blocksNonHttpSchemes() {
        assertThatThrownBy(() -> guarded.validateTarget("file:///etc/passwd"))
                .isInstanceOf(HttpStepActivity.NonRetryable.class)
                .hasMessageContaining("http or https");
    }

    @Test
    void aBlockedTargetIsNotRetried() {
        // Retrying an SSRF attempt would just repeat it; the failure is settled.
        assertThat(new HttpStepActivity.NonRetryable("x"))
                .isInstanceOf(io.continuum.core.activity.NonRetryableFailure.class);
    }

    @Test
    void selfHostedDeploymentsCanOptIntoPrivateTargets() {
        // Running the engine beside your own services is a legitimate setup.
        assertThatCode(() -> permissive.validateTarget("http://10.0.0.5/internal"))
                .doesNotThrowAnyException();
    }
}
