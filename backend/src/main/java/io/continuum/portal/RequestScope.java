package io.continuum.portal;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the caller's tenant from the authenticated session — never from a
 * request parameter.
 *
 * <p>Console endpoints used to accept {@code ?developerId=...}, which let any
 * caller read any tenant's data. Controllers now call {@link #developerId} so the
 * tenant is always the signed-in developer. An {@code OPERATOR} session returns
 * {@code null}, which repositories interpret as "engine-wide" — the operator is
 * the only role allowed to see across tenants.
 */
public final class RequestScope {

    private RequestScope() {
    }

    /** The signed-in developer, or {@code null} for an operator (engine-wide) session. */
    public static String developerId(HttpServletRequest request) {
        Object v = request.getAttribute(ConsoleAuthFilter.DEVELOPER_ID_ATTRIBUTE);
        return v == null ? null : v.toString();
    }

    /** True when the caller is the engine operator rather than a tenant. */
    public static boolean isOperator(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(ConsoleAuthFilter.OPERATOR_ATTRIBUTE));
    }

    /**
     * Guards a tenant-owned record: an operator may read anything, a developer only
     * their own rows. {@code ownerId} is the developer id stored on the record.
     */
    public static void requireOwner(HttpServletRequest request, String ownerId) {
        if (isOperator(request)) {
            return;
        }
        String caller = developerId(request);
        if (caller == null || ownerId == null || !caller.equals(ownerId)) {
            throw new ForbiddenException();
        }
    }

    /** Thrown when a caller reaches for another tenant's record. */
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException() {
            super("This resource belongs to another account.");
        }
    }
}
