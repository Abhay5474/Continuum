package io.continuum.gateway;

import io.continuum.persistence.entity.GatewayRequestLogEntity;
import io.continuum.persistence.repository.GatewayRequestLogRepository;
import org.springframework.stereotype.Component;

/**
 * Writes a request's log row and remembers its id for the rest of the request.
 *
 * <p>The request runs on one thread from {@link GatewayService#chat} to its
 * return — the hedged path waits for its race — so a thread-local carries the
 * id out without threading a parameter through every stage. Cleared on entry
 * and exit, because the thread is pooled.
 */
@Component
public class RequestLog {

    private static final ThreadLocal<Long> LOGGED = new ThreadLocal<>();

    private final GatewayRequestLogRepository logRepo;

    public RequestLog(GatewayRequestLogRepository logRepo) {
        this.logRepo = logRepo;
    }

    /** Saves the row and records its id as this request's. */
    public GatewayRequestLogEntity save(GatewayRequestLogEntity row) {
        GatewayRequestLogEntity saved = logRepo.save(row);
        if (saved != null && saved.getId() != null) {
            LOGGED.set(saved.getId());
        }
        return saved;
    }

    /** The id logged for the request running on this thread, if any. */
    public Long lastId() {
        return LOGGED.get();
    }

    /** Forgets it. Called on entry and exit, because request threads are pooled. */
    public void clear() {
        LOGGED.remove();
    }
}
