package io.continuum.persistence.repository;

import io.continuum.persistence.entity.GatewayRequestLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface GatewayRequestLogRepository extends JpaRepository<GatewayRequestLogEntity, Long> {

    Page<GatewayRequestLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countBySuccess(boolean success);

    @Query("select coalesce(sum(g.failoverCount),0) from GatewayRequestLogEntity g")
    long totalFailovers();

    @Query("select coalesce(sum(g.costUsd),0) from GatewayRequestLogEntity g")
    double totalCost();

    @Query("select coalesce(sum(g.tokens),0) from GatewayRequestLogEntity g")
    long totalTokens();

    @Query("select g.chosenProvider as provider, count(g) as requests, coalesce(sum(g.costUsd),0) as cost " +
           "from GatewayRequestLogEntity g where g.success = true group by g.chosenProvider")
    List<ProviderUsage> usageByProvider();

    interface ProviderUsage {
        String getProvider();
        long getRequests();
        double getCost();
    }

    // --- developer-scoped (portal) ---

    Page<GatewayRequestLogEntity> findByDeveloperIdOrderByCreatedAtDesc(String developerId, Pageable pageable);

    long countByDeveloperIdAndSuccess(String developerId, boolean success);

    @Query("select coalesce(sum(g.failoverCount),0) from GatewayRequestLogEntity g where g.developerId = :dev")
    long totalFailoversForDeveloper(String dev);

    @Query("select coalesce(sum(g.costUsd),0) from GatewayRequestLogEntity g where g.developerId = :dev")
    double totalCostForDeveloper(String dev);

    @Query("select coalesce(sum(g.tokens),0) from GatewayRequestLogEntity g where g.developerId = :dev")
    long totalTokensForDeveloper(String dev);

    /** Per-provider outcomes for a developer — the arm statistics for the bandit. */
    @Query("select g.chosenProvider as provider, " +
           "sum(case when g.success = true then 1 else 0 end) as successes, " +
           "sum(case when g.success = false then 1 else 0 end) as failures, " +
           "avg(g.latencyMs) as avgLatency, avg(g.costUsd) as avgCost " +
           "from GatewayRequestLogEntity g where g.developerId = :dev and g.chosenProvider is not null " +
           "group by g.chosenProvider")
    List<ProviderOutcome> providerOutcomesForDeveloper(String dev);

    interface ProviderOutcome {
        String getProvider();
        long getSuccesses();
        long getFailures();
        double getAvgLatency();
        double getAvgCost();
    }
}
