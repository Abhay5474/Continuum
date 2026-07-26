package io.continuum.portal;

/**
 * The tenant whose work the current thread is doing.
 *
 * <p>Some cross-cutting concerns need to know the tenant in places that are far
 * from any controller — fault injection is consulted deep inside providers,
 * activities and outbox sinks, none of which should have to carry a developer id
 * through their signatures just so a drill can be scoped.
 *
 * <p>Set it at the two places work enters the system: an authenticated request,
 * and a worker picking up an activity. Both must clear it in a {@code finally},
 * because these are pooled threads and a stale tenant here would attribute one
 * customer's work to another.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** The current tenant, or {@code null} for engine-wide/operator work. */
    public static String developerId() {
        return CURRENT.get();
    }

    public static void set(String developerId) {
        if (developerId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(developerId);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs {@code body} as {@code developerId}, restoring the previous tenant after. */
    public static <T> T callAs(String developerId, java.util.concurrent.Callable<T> body) throws Exception {
        String previous = CURRENT.get();
        set(developerId);
        try {
            return body.call();
        } finally {
            set(previous);
        }
    }
}
