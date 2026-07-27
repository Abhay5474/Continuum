package io.continuum.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pin exists to make the address that was security-checked the same address
 * that gets connected to. Two properties carry that: a pinned host resolves to
 * exactly one answer, and a pin never escapes the thread that set it.
 */
class PinnedDnsResolverTest {

    @AfterEach
    void tearDown() {
        PinnedDnsResolver.unpin("example.test");
        PinnedDnsResolver.unpin("other.test");
    }

    @Test
    @DisplayName("the JDK loaded the provider — without it the pin is decoration")
    void providerIsInstalled() throws Exception {
        // Force a lookup so the JDK instantiates providers, then confirm ours
        // was among them. If this fails the SPI registration is broken and the
        // rebinding guard silently degrades.
        try {
            InetAddress.getByName("localhost");
        } catch (Exception ignored) {
            // Resolution itself is not what is under test.
        }
        assertThat(PinnedDnsResolver.installed())
                .as("META-INF/services/java.net.spi.InetAddressResolverProvider must register the provider")
                .isTrue();
    }

    @Test
    @DisplayName("a pinned host resolves to exactly the vetted address")
    void pinnedHostResolvesToOneAddress() throws Exception {
        InetAddress vetted = InetAddress.getByAddress("example.test",
                new byte[]{(byte) 93, (byte) 184, (byte) 216, (byte) 34});
        PinnedDnsResolver.pin("example.test", vetted);

        assertThat(InetAddress.getAllByName("example.test")).containsExactly(vetted);
    }

    @Test
    @DisplayName("pinning is case-insensitive, because hostnames are")
    void pinIsCaseInsensitive() throws Exception {
        InetAddress vetted = InetAddress.getByAddress("example.test", new byte[]{93, (byte) 184, (byte) 216, 34});
        PinnedDnsResolver.pin("EXAMPLE.TEST", vetted);

        assertThat(InetAddress.getAllByName("example.test")).containsExactly(vetted);
    }

    @Test
    @DisplayName("unpinning restores ordinary resolution")
    void unpinReleases() throws Exception {
        InetAddress vetted = InetAddress.getByAddress("example.test", new byte[]{93, (byte) 184, (byte) 216, 34});
        PinnedDnsResolver.pin("example.test", vetted);
        PinnedDnsResolver.unpin("example.test");

        // No pin remains, so the platform resolver is consulted again — which for
        // a non-existent host means it fails rather than returning the old answer.
        assertThat(PinnedDnsResolver.pinnedOnly("example.test")).isEmpty();
    }

    @Test
    @DisplayName("a pin never leaks to another thread")
    void pinsAreThreadLocal() throws Exception {
        InetAddress vetted = InetAddress.getByAddress("example.test", new byte[]{93, (byte) 184, (byte) 216, 34});
        PinnedDnsResolver.pin("example.test", vetted);

        // Activities run on a shared pool. A pin visible to a sibling thread
        // would silently redirect an unrelated request to somewhere it was never
        // checked against — worse than the hole being closed.
        AtomicInteger seen = new AtomicInteger(-1);
        CountDownLatch done = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            seen.set(PinnedDnsResolver.pinnedOnly("example.test").size());
            done.countDown();
        });
        other.start();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(seen.get()).isZero();
        assertThat(PinnedDnsResolver.pinnedOnly("example.test")).hasSize(1);
    }

    @Test
    @DisplayName("unrelated hosts are untouched while a pin is held")
    void otherHostsFallThrough() throws Exception {
        InetAddress vetted = InetAddress.getByAddress("example.test", new byte[]{93, (byte) 184, (byte) 216, 34});
        PinnedDnsResolver.pin("example.test", vetted);

        assertThat(PinnedDnsResolver.pinnedOnly("other.test")).isEmpty();
        // localhost still resolves normally, so the provider is a pass-through
        // for everything it was not asked about.
        assertThat(InetAddress.getByName("localhost")).isNotNull();
    }

    @Test
    @DisplayName("a null host or address is ignored rather than throwing")
    void nullsAreIgnored() {
        assertThat(PinnedDnsResolver.pin(null, null)).isFalse();
        PinnedDnsResolver.unpin(null);
    }
}
