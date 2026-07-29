package io.continuum.admission;

import io.continuum.persistence.entity.CostAdmissionSettingEntity;
import io.continuum.persistence.repository.CostAdmissionSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-tenant cost-aware admission, and what it is currently charging. */
@Service
public class CostAdmissionService {

    private final CostAdmissionSettingRepository repo;
    private final CostAwareLimiter limiter = new CostAwareLimiter();

    public CostAdmissionService(CostAdmissionSettingRepository repo) {
        this.repo = repo;
    }

    /** Raised when a caller has spent their token allowance rather than their request count. */
    public static class CostLimitedException extends RuntimeException {
        private final CostAwareLimiter.Decision decision;

        public CostLimitedException(CostAwareLimiter.Decision decision) {
            super(decision.reason());
            this.decision = decision;
        }

        public CostAwareLimiter.Decision decision() {
            return decision;
        }
    }

    @Transactional(readOnly = true)
    public CostAdmissionSettingEntity settingsFor(String developerId) {
        return repo.findById(developerId)
                .orElseGet(() -> new CostAdmissionSettingEntity(developerId));
    }

    @Transactional(readOnly = true)
    public boolean enabled(String developerId) {
        return developerId != null
                && repo.findById(developerId).map(CostAdmissionSettingEntity::isEnabled).orElse(false);
    }

    /**
     * Reserves capacity for a request, or refuses it.
     *
     * @return the reservation, or null when the feature is off — null means the
     *         caller does nothing differently, which is the pre-existing path
     * @throws CostLimitedException when the caller has no room
     */
    public CostAwareLimiter.Ticket reserve(String developerId, int promptTokens, Integer maxTokens) {
        CostAdmissionSettingEntity cfg = settingsFor(developerId);
        if (!cfg.isEnabled()) {
            return null;
        }
        CostAwareLimiter.Outcome outcome = limiter.reserve(developerId, promptTokens, maxTokens,
                cfg.getRequestsPerMin(), cfg.getTokensPerMin());
        if (!outcome.allowed()) {
            throw new CostLimitedException(outcome.decision());
        }
        return outcome.ticket();
    }

    @Transactional
    public Map<String, Object> configure(String developerId, Boolean enabled,
                                         Integer requestsPerMin, Integer tokensPerMin) {
        CostAdmissionSettingEntity cfg = repo.findById(developerId)
                .orElseGet(() -> new CostAdmissionSettingEntity(developerId));
        if (enabled != null) {
            cfg.setEnabled(enabled);
        }
        if (requestsPerMin != null) {
            cfg.setRequestsPerMin(requestsPerMin);
        }
        if (tokensPerMin != null) {
            cfg.setTokensPerMin(tokensPerMin);
        }
        repo.save(cfg);
        return status(developerId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(String developerId) {
        CostAdmissionSettingEntity cfg = settingsFor(developerId);
        limiter.sweep();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.isEnabled());
        out.put("requestsPerMin", cfg.getRequestsPerMin());
        out.put("tokensPerMin", cfg.getTokensPerMin());
        out.put("assumedCompletionTokens", CostAwareLimiter.ASSUMED_COMPLETION_TOKENS);
        out.put("reservationTtlSeconds", CostAwareLimiter.RESERVATION_TTL.toSeconds());
        out.put("callers", limiter.describe(developerId, cfg.getRequestsPerMin(), cfg.getTokensPerMin()));
        return out;
    }

    public Map<String, Object> reset(String developerId) {
        limiter.reset(developerId);
        return status(developerId);
    }
}
