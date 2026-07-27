package io.continuum.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Pins a hostname to the exact address that was security-checked.
 *
 * <p>Closes a DNS-rebinding hole. The outbound HTTP activity resolves a
 * customer-supplied hostname, verifies every returned address is public, and
 * then hands the <em>hostname</em> to {@code HttpClient} — which resolves it
 * again. An attacker controlling DNS with a zero TTL answers with a public
 * address for the check and {@code 169.254.169.254} for the connection, and the
 * guard has verified nothing.
 *
 * <p>The fix has to make the checked answer and the connected answer the same
 * answer. Java's {@code HttpClient} exposes no per-request address override, so
 * this installs an {@link InetAddressResolver} via the JDK's resolver SPI
 * (JEP 418, Java 18+) that consults a thread-local pin. While a pin is held,
 * lookups for that host return only the vetted address; every other lookup falls
 * through to the platform resolver untouched.
 *
 * <p>Thread-local rather than global because the pin must not leak across
 * concurrent requests — two activities calling different hosts at the same
 * moment must not see each other's pins. The activity is responsible for
 * clearing it in a {@code finally}, since these run on pooled threads.
 *
 * <p>Registered through {@code META-INF/services}. When the provider is not
 * installed the pinning methods are inert and the guard degrades to its previous
 * check-then-resolve behaviour rather than failing closed — an unresolvable
 * deployment concern should not take the engine down.
 */
public class PinnedDnsResolver extends InetAddressResolverProvider {

    /** host (lowercase) → the single address that passed the safety check. */
    private static final ThreadLocal<Map<String, InetAddress>> PINS =
            ThreadLocal.withInitial(ConcurrentHashMap::new);

    /** True once the JDK has actually loaded this provider. */
    private static volatile boolean installed;

    /**
     * Pins {@code host} to {@code address} for this thread.
     *
     * @return whether the pin will actually be honoured — false when the
     *         provider was not installed, so a caller can decide what to do
     */
    public static boolean pin(String host, InetAddress address) {
        if (host == null || address == null) {
            return false;
        }
        PINS.get().put(host.toLowerCase(java.util.Locale.ROOT), address);
        return installed;
    }

    /** Releases a pin. Must run in a {@code finally}: these are pooled threads. */
    public static void unpin(String host) {
        if (host != null) {
            Map<String, InetAddress> map = PINS.get();
            map.remove(host.toLowerCase(java.util.Locale.ROOT));
            if (map.isEmpty()) {
                PINS.remove();
            }
        }
    }

    /** Whether the JDK loaded this provider — surfaced so startup can log it. */
    public static boolean installed() {
        return installed;
    }

    @Override
    public String name() {
        return "continuum-pinned";
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        installed = true;
        InetAddressResolver platform = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy)
                    throws UnknownHostException {
                InetAddress pinned = PINS.get().get(host.toLowerCase(java.util.Locale.ROOT));
                if (pinned != null) {
                    // Exactly one answer: the address the guard approved. No
                    // second resolution, so nothing to rebind.
                    return Stream.of(pinned);
                }
                return platform.lookupByName(host, policy);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return platform.lookupByAddress(addr);
            }
        };
    }

    /** Visible for tests: how many pins this thread currently holds. */
    static int pinCount() {
        return PINS.get().size();
    }

    /** Visible for tests: resolve through the pin map only. */
    static List<InetAddress> pinnedOnly(String host) {
        InetAddress a = PINS.get().get(host.toLowerCase(java.util.Locale.ROOT));
        return a == null ? List.of() : List.of(a);
    }
}
