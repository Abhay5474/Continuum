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
}
