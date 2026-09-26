package io.continuum.registry.catalog;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where the catalogue keeps what is not a model row: per-provider state, the
 * record of each check, the change log, and the lease that keeps checks one at
 * a time. An interface so the catalogue's decisions can be tested without a
 * database.
 */
public interface ModelCatalogStore {

    ProviderState state(String provider);

    List<ProviderState> states();

    void saveState(ProviderState s);

    long startRun(String trigger, String requestedBy);

    void finishRun(long id, String outcome, String summaryJson, int listCalls, int probeCalls);

    /** The latest run started by a person or the schedule (confirmations excluded). */
    Optional<Run> lastRun();

    List<Run> recentRuns(int limit);

    void event(String provider, String model, String type, String detail, Long runId);

    List<Event> events(int limit);

    /** True when this caller now holds the lease; an expired holder is taken over. */
    boolean acquireLease(String holder, Duration ttl);

    void releaseLease(String holder);

    /** Mutable on purpose: a check updates it field by field and saves it once. */
    final class ProviderState {
        public final String provider;
        public Instant lastListOkAt;
        public Instant lastAttemptAt;
        public String lastError;
        public int listedCount;
        public String defaultModel;
        public String pinnedModel;
        public Instant lastConfirmAt;

        public ProviderState(String provider) {
            this.provider = provider;
        }
    }

    record Run(long id, String trigger, String requestedBy, Instant startedAt, Instant finishedAt,
               String outcome, String summary, int listCalls, int probeCalls) {
    }

    record Event(long id, String provider, String model, String type, String detail, Instant createdAt) {
    }
}
