package io.continuum.portal;

import io.continuum.persistence.repository.DeveloperAuthRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which developer sessions have been ended early.
 *
 * <p>Session tokens are signed and self-expiring, which is what lets every
 * request be authenticated without a lookup — and what used to mean a session
 * could not be ended before its twelve hours were up. Changing your password
 * left a stolen session working; deleting your account left your token
 * reaching tenant-scoped endpoints for an account that no longer existed.
 *
 * <p>One timestamp per account restores that: sessions issued before it are
 * refused. It is read on every authenticated request, so it is cached briefly;
 * a revocation made on this server takes effect at once, and on another server
 * within {@link #TTL_MS}.
 */
@Component
public class SessionRevocations {

    static final long TTL_MS = 15_000;

    private final DeveloperAuthRepository auth;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(boolean exists, long validAfterEpoch, long readAt) {
    }

    public SessionRevocations(DeveloperAuthRepository auth) {
        this.auth = auth;
    }

    /** True when a developer session issued at {@code issuedAtEpoch} may no longer be used. */
    public boolean revoked(String developerId, long issuedAtEpoch) {
        long now = System.currentTimeMillis();
        Entry e = cache.get(developerId);
        if (e == null || now - e.readAt() > TTL_MS) {
            e = auth.findById(developerId)
                    .map(a -> new Entry(true, a.getSessionsValidAfter() == null ? 0
                            : a.getSessionsValidAfter().getEpochSecond(), now))
                    .orElse(new Entry(false, 0, now));
            if (cache.size() > 50_000) {
                cache.clear(); // bounded; a miss is one indexed read
            }
            cache.put(developerId, e);
        }
        return !e.exists() || issuedAtEpoch < e.validAfterEpoch();
    }

    /** Ends every session for this developer issued before now. */
    public Instant revokeAll(String developerId) {
        Instant soonest = Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant[] at = {soonest};
        auth.findById(developerId).ifPresent(a -> {
            // Strictly after the previous cut-off: the session handed out by the
            // last revocation was issued at that cut-off, and a second revocation
            // in the same second must end it too.
            Instant previous = a.getSessionsValidAfter();
            if (previous != null && !soonest.isAfter(previous)) {
                at[0] = previous.plusSeconds(1);
            }
            a.setSessionsValidAfter(at[0]);
            auth.save(a);
        });
        cache.remove(developerId);
        return at[0];
    }

    /** For account deletion: forget what we knew, so the next check sees it gone. */
    public void forget(String developerId) {
        cache.remove(developerId);
    }
}
