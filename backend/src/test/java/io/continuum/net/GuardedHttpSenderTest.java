package io.continuum.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Target URLs are supplied by customers and called by the server, so without
 * this check the engine is a confused deputy able to reach internal services and
 * cloud metadata endpoints.
 *
 * <p>These assertions moved here from the workflow activity when the guard was
 * extracted: they belong to the guard, not to one of its two callers.
 */
class GuardedHttpSenderTest {

    private final GuardedHttpSender guarded = new GuardedHttpSender(false);
    private final GuardedHttpSender permissive = new GuardedHttpSender(true);

    @Test
    @DisplayName("a public target is allowed")
    void allowsAPublicTarget() {
        // An address literal, so the check is exercised without depending on DNS
        // being reachable from the test environment.
        assertThatCode(() -> guarded.resolve("https://93.184.216.34/hook"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the approved address is carried forward, not just validated")
    void resolveReturnsTheApprovedAddress() throws Exception {
        var target = guarded.resolve("https://93.184.216.34/hook");

        // Carrying it is the DNS-rebinding mitigation: the connection uses this
        // address rather than resolving the name a second time.
        assertThat(target.address()).isNotNull();
        assertThat(target.host()).isEqualTo("93.184.216.34");
    }

    @Test
    @DisplayName("loopback is refused")
    void blocksLoopback() {
        assertThatThrownBy(() -> guarded.resolve("http://127.0.0.1:8080/internal"))
                .isInstanceOf(GuardedHttpSender.NonRetryable.class)
                .hasMessageContaining("private address");
    }

    @Test
    @DisplayName("private ranges are refused")
    void blocksPrivateRanges() {
        for (String host : new String[] {"http://10.0.0.5/x", "http://192.168.1.10/x", "http://172.16.0.9/x"}) {
            assertThatThrownBy(() -> guarded.resolve(host))
                    .isInstanceOf(GuardedHttpSender.NonRetryable.class);
        }
    }

    @Test
    @DisplayName("the cloud metadata endpoint is refused")
    void blocksCloudMetadataLinkLocal() {
        assertThatThrownBy(() -> guarded.resolve("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(GuardedHttpSender.NonRetryable.class);
    }

    @Test
    @DisplayName("non-HTTP schemes are refused")
    void blocksNonHttpSchemes() {
        assertThatThrownBy(() -> guarded.resolve("file:///etc/passwd"))
                .isInstanceOf(GuardedHttpSender.NonRetryable.class)
                .hasMessageContaining("http or https");
    }

    @Test
    @DisplayName("a refused target is settled, not retried")
    void aBlockedTargetIsNotRetried() {
        // Retrying an SSRF attempt would just repeat it.
        assertThat(new GuardedHttpSender.NonRetryable("x"))
                .isInstanceOf(io.continuum.core.activity.NonRetryableFailure.class);
    }

    @Test
    @DisplayName("a self-hosted deployment can opt into private targets")
    void selfHostedDeploymentsCanOptIn() {
        // Running the engine beside your own services is a legitimate setup.
        assertThatCode(() -> permissive.resolve("http://10.0.0.5/internal"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("opting in skips pinning too, since there is nothing to protect against")
    void permissiveTargetsCarryNoPin() throws Exception {
        assertThat(permissive.resolve("http://10.0.0.5/internal").address()).isNull();
    }
}
