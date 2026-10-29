package io.continuum.apps;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Which application the current thread is working for, and which applications'
 * data it may read.
 *
 * <p>An account can run several applications, each with its own API key. What
 * one of them captures — requests, memory, cached answers, experience — is that
 * application's, and must neither show up in another's console view nor be
 * recalled into another's answers. Tenancy is still the developer id; this is
 * one level below it.
 *
 * <p>Two separate things are held here, because they differ by caller:
 * <ul>
 *   <li><b>write</b>: the app new rows are tagged with. Set only when work is
 *       done on an application's behalf — a request carrying its key, or a
 *       worker running its workflow. The console writes untagged rows.</li>
 *   <li><b>read</b>: which rows queries see. An application sees its own rows
 *       and the untagged ones (made in the console, or before apps existed),
 *       never another application's. The console sees whatever the person
 *       picked in its app selector.</li>
 * </ul>
 *
 * <p>Pooled threads: whoever sets this clears it in a {@code finally}.
 */
public final class AppContext {

    /** How rows are filtered by app. */
    public enum Read {
        /** No filtering: every app's rows and the untagged ones. */
        ALL(0),
        /** Only the chosen app's rows. */
        ONLY(1),
        /** The chosen app's rows and the untagged ones — what an application sees. */
        OWN_AND_SHARED(2),
        /** Only untagged rows. */
        UNTAGGED(3);

        final int code;

        Read(int code) {
            this.code = code;
        }
    }

    private record State(Long write, Read read, Long app) {
    }

    private static final State NONE = new State(null, Read.ALL, null);

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private AppContext() {
    }

    /** The app new rows belong to, or {@code null} for untagged. */
    public static Long writeApp() {
        return state().write();
    }

    static Read readMode() {
        return state().read();
    }

    static Long readApp() {
        return state().app();
    }

    /**
     * Whether something tagged {@code appId} is visible to this thread: the
     * database filter's rule, for the few things kept in memory instead.
     */
    public static boolean visible(Long appId) {
        State s = state();
        return switch (s.read()) {
            case ALL -> true;
            case ONLY -> appId != null && appId.equals(s.app());
            case OWN_AND_SHARED -> appId == null || appId.equals(s.app());
            case UNTAGGED -> appId == null;
        };
    }

    /** {@link #writeApp()} as a map-key part: the id, or "-" for untagged. */
    public static String writeKey() {
        Long app = writeApp();
        return app == null ? "-" : app.toString();
    }

    /** The inverse of {@link #writeKey()}. */
    public static Long fromKey(String key) {
        return key == null || key.equals("-") ? null : Long.valueOf(key);
    }

    /** Work done for an application: tags its rows, and reads its own and the shared ones. */
    public static void forApp(Long appId) {
        if (appId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(new State(appId, Read.OWN_AND_SHARED, appId));
        }
    }

    /**
     * The console: reads what the selector names. {@code null} or {@code "all"}
     * is every app; {@code "untagged"} is console-made and older data; a number
     * is one app.
     *
     * <p>Looking at one app, the console also writes as it. Otherwise a memory
     * stored, a workflow started or a test request sent from the console while
     * an app is in view is saved untagged and then hidden by that same view: the
     * button appears to do nothing. Only an app the account owns is written as;
     * any other id shows nothing and tags nothing.
     */
    public static void forConsole(String selector, java.util.function.Predicate<Long> owned) {
        Read read = parseRead(selector);
        Long app = parseApp(selector);
        Long write = read == Read.ONLY && app != null && owned.test(app) ? app : null;
        CURRENT.set(new State(write, read, read == Read.ONLY && write == null ? null : app));
    }

    /** As {@link #forConsole(String, java.util.function.Predicate)}, trusting no app: for reads only. */
    public static void forConsole(String selector) {
        forConsole(selector, id -> false);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Runs {@code body} strictly as one app — reading only its rows and tagging
     * what it writes — for background work done on an app's data, such as
     * consolidating its memory. {@code null} is the untagged rows.
     */
    public static <T> T callAs(Long appId, Supplier<T> body) {
        State previous = CURRENT.get();
        CURRENT.set(new State(appId, appId == null ? Read.UNTAGGED : Read.ONLY, appId));
        try {
            return body.get();
        } finally {
            restore(previous);
        }
    }

    /** Reads every app's rows while {@code body} runs: for limits that belong to the whole account. */
    public static <T> T unscoped(Supplier<T> body) {
        State previous = CURRENT.get();
        State now = state();
        CURRENT.set(new State(now.write(), Read.ALL, null));
        try {
            return body.get();
        } finally {
            restore(previous);
        }
    }

    /** What this thread holds, to carry onto another thread. */
    public static Object capture() {
        return CURRENT.get();
    }

    /** Puts back what {@link #capture()} returned; {@code null} clears. */
    public static void restore(Object captured) {
        if (captured instanceof State s) {
            CURRENT.set(s);
        } else {
            CURRENT.remove();
        }
    }

    /** {@code body}, run on another thread with this thread's app and tenant. */
    public static <T> Callable<T> carry(Callable<T> body) {
        Object app = capture();
        String tenant = io.continuum.portal.TenantContext.developerId();
        return () -> {
            restore(app);
            io.continuum.portal.TenantContext.set(tenant);
            try {
                return body.call();
            } finally {
                clear();
                io.continuum.portal.TenantContext.clear();
            }
        };
    }

    /** As {@link #carry(Callable)}, for a {@link Runnable}. */
    public static Runnable carry(Runnable body) {
        Callable<Object> c = carry(() -> {
            body.run();
            return null;
        });
        return () -> {
            try {
                c.call();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private static State state() {
        State s = CURRENT.get();
        return s == null ? NONE : s;
    }

    private static Read parseRead(String selector) {
        if (selector == null || selector.isBlank() || selector.equalsIgnoreCase("all")) {
            return Read.ALL;
        }
        if (selector.equalsIgnoreCase("untagged")) {
            return Read.UNTAGGED;
        }
        // An id that does not parse names no app; showing everything instead
        // would be the one wrong answer, so it shows nothing.
        return Read.ONLY;
    }

    private static Long parseApp(String selector) {
        if (selector == null) {
            return null;
        }
        try {
            return Long.parseLong(selector.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
